// JNI-мост между Kotlin и llama.cpp (+ libmtmd для картинок и видео).
// Вся работа с моделью идёт на одном фоновом потоке (см. LlamaEngine.kt),
// только stop() может вызываться из любого потока.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define TAG "NeuroPocket"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOG_WRITE(prio, text) __android_log_write(prio, TAG, text)
#define PRIO_DEBUG ANDROID_LOG_DEBUG
#define PRIO_INFO ANDROID_LOG_INFO
#define PRIO_WARN ANDROID_LOG_WARN
#define PRIO_ERROR ANDROID_LOG_ERROR
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) LOGI(__VA_ARGS__)
#define LOGE(...) LOGI(__VA_ARGS__)
#define PRIO_DEBUG 3
#define PRIO_INFO 4
#define PRIO_WARN 5
#define PRIO_ERROR 6
#define LOG_WRITE(prio, text) do { if ((prio) >= PRIO_WARN) fputs(text, stderr); } while (0)
#endif

// Коды результата generate()
static constexpr int RES_TEMPLATE_ERROR = -1;
static constexpr int RES_CONTEXT_FULL   = -2;
static constexpr int RES_DECODE_ERROR   = -3;
static constexpr int RES_BAD_HANDLE     = -4;
static constexpr int RES_MEDIA_ERROR    = -5;

static std::atomic<bool> g_abort{false};

// Кусок промпта, уже лежащий в KV-кэше: текст (токены) или картинка (id).
struct Piece {
    bool media = false;
    std::vector<llama_token> tokens;  // для текста
    std::string id;                   // для картинки
    llama_pos n_pos = 0;              // сколько позиций занимает
    const mtmd_input_chunk *chunk = nullptr; // только на время generate()
};

struct Session {
    llama_model         *model   = nullptr;
    llama_context       *ctx     = nullptr;
    const llama_vocab   *vocab   = nullptr;
    mtmd_context        *mctx    = nullptr; // модуль зрения (mmproj), может отсутствовать
    std::string          tmpl;              // шаблон чата из метаданных модели
    std::vector<Piece>   cached;            // что сейчас лежит в KV-кэше
    llama_pos            n_past  = 0;
    int                  n_ctx   = 0;
    int                  n_batch = 0;
};

static void log_callback(ggml_log_level level, const char *text, void *) {
    int prio = PRIO_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = PRIO_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = PRIO_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = PRIO_INFO;
    LOG_WRITE(prio, text);
}

static bool abort_callback(void *) { return g_abort.load(); }

static std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (!s) return {};
    // String.getBytes("UTF-8") корректно передаёт эмодзи и символы вне BMP.
    jclass strCls = env->GetObjectClass(s);
    jmethodID getBytes = env->GetMethodID(strCls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray) env->CallObjectMethod(s, getBytes, enc);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(strCls);
    std::string out;
    if (bytes) {
        jsize len = env->GetArrayLength(bytes);
        out.resize(len);
        env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(&out[0]));
        env->DeleteLocalRef(bytes);
    }
    return out;
}

// Длина самого длинного префикса, состоящего из целых UTF-8 символов.
static size_t utf8_complete_prefix(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back >= need ? n : n - back;
    }
    return n;
}

static std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(std::max(n, 0));
    if (n > 0) {
        llama_tokenize(vocab, text.data(), (int32_t) text.size(), tokens.data(), n, true, true);
    }
    return tokens;
}

static std::string token_to_piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(-n, '\0');
        n = llama_token_to_piece(vocab, t, &big[0], (int32_t) big.size(), 0, false);
        big.resize(std::max(n, 0));
        return big;
    }
    return std::string(buf, n);
}

static bool format_chat(const Session *s,
                        const std::vector<std::string> &roles,
                        const std::vector<std::string> &contents,
                        std::string &out) {
    std::vector<llama_chat_message> msgs(roles.size());
    size_t total = 0;
    for (size_t i = 0; i < roles.size(); ++i) {
        msgs[i] = {roles[i].c_str(), contents[i].c_str()};
        total += contents[i].size() + 64;
    }
    std::vector<char> buf(total * 2 + 1024);

    const char *candidates[] = {s->tmpl.empty() ? nullptr : s->tmpl.c_str(), "chatml"};
    for (const char *tmpl : candidates) {
        if (!tmpl) continue;
        int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                            buf.data(), (int32_t) buf.size());
        if (len < 0) {
            LOGW("Шаблон чата не поддерживается, пробуем следующий");
            continue;
        }
        if (len > (int) buf.size()) {
            buf.resize(len + 1);
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                            buf.data(), (int32_t) buf.size());
        }
        out.assign(buf.data(), len);
        return true;
    }
    return false;
}

static void clear_cache(Session *s) {
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
    s->n_past = 0;
}

// Прогоняет токены через модель с явными позициями, начиная с pos.
static int decode_tokens(Session *s, const llama_token *tokens, int n, llama_pos pos, bool logits_last) {
    llama_batch batch = llama_batch_init(s->n_batch, 0, 1);
    int rc = 0;
    for (int i = 0; i < n && rc == 0; i += s->n_batch) {
        int cnt = std::min(s->n_batch, n - i);
        batch.n_tokens = cnt;
        for (int j = 0; j < cnt; ++j) {
            batch.token[j] = tokens[i + j];
            batch.pos[j] = pos + i + j;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (logits_last && i + j == n - 1) ? 1 : 0;
        }
        rc = llama_decode(s->ctx, batch);
    }
    llama_batch_free(batch);
    return rc;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_neuropocket_app_engine_LlamaNative_init(JNIEnv *env, jobject, jstring jlibDir) {
    llama_log_set(log_callback, nullptr);
    mtmd_helper_log_set(log_callback, nullptr);
    std::string dir = jstring_to_std(env, jlibDir);
    LOGI("Загрузка CPU-бэкендов из %s", dir.c_str());
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_com_neuropocket_app_engine_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    std::string info;
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        if (!info.empty()) info += ", ";
        info += ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    info += " | ";
    info += llama_print_system_info();
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_neuropocket_app_engine_LlamaNative_mediaMarker(JNIEnv *env, jobject) {
    return env->NewStringUTF(mtmd_default_marker());
}

JNIEXPORT jlong JNICALL
Java_com_neuropocket_app_engine_LlamaNative_load(JNIEnv *env, jobject, jstring jpath, jstring jmmproj,
                                                 jint nCtx, jint nThreads, jint nGpuLayers) {
    std::string path = jstring_to_std(env, jpath);
    std::string mmproj = jstring_to_std(env, jmmproj);
    g_abort.store(false);

    llama_model_params mp = llama_model_default_params();
    // 0 — всё на процессоре (телефон); >0 — сколько слоёв отдать видеокарте (компьютер)
    mp.n_gpu_layers = nGpuLayers;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("Не удалось загрузить модель %s", path.c_str());
        return 0;
    }

    int train_ctx = llama_model_n_ctx_train(model);
    int ctx_size = nCtx;
    if (train_ctx > 0 && ctx_size > train_ctx) ctx_size = train_ctx;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) ctx_size;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.abort_callback = abort_callback;
    cp.abort_callback_data = nullptr;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("Не удалось создать контекст (возможно, не хватает памяти)");
        llama_model_free(model);
        return 0;
    }

    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = (int) llama_n_ctx(ctx);
    s->n_batch = (int) cp.n_batch;
    const char *t = llama_model_chat_template(model, nullptr);
    if (t) s->tmpl = t;

    if (!mmproj.empty()) {
        mtmd_context_params mparams = mtmd_context_params_default();
        mparams.use_gpu = nGpuLayers > 0;
        mparams.n_threads = nThreads;
        mparams.print_timings = false;
        mparams.warmup = false;
        s->mctx = mtmd_init_from_file(mmproj.c_str(), model, mparams);
        if (!s->mctx) {
            LOGE("Не удалось загрузить модуль зрения %s", mmproj.c_str());
        }
    }
    LOGI("Модель загружена, n_ctx=%d, зрение: %s", s->n_ctx, s->mctx ? "да" : "нет");
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jboolean JNICALL
Java_com_neuropocket_app_engine_LlamaNative_hasVision(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    return (s && s->mctx && mtmd_support_vision(s->mctx)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_neuropocket_app_engine_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    if (s->mctx) mtmd_free(s->mctx);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

JNIEXPORT jstring JNICALL
Java_com_neuropocket_app_engine_LlamaNative_modelInfo(JNIEnv *env, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    char name[256] = {0};
    if (llama_model_meta_val_str(s->model, "general.name", name, sizeof(name)) < 0) name[0] = 0;
    double params_b = (double) llama_model_n_params(s->model) / 1e9;
    double size_gb = (double) llama_model_size(s->model) / (1024.0 * 1024.0 * 1024.0);
    char out[768];
    snprintf(out, sizeof(out), "%s\n%s · %.2fB параметров · %.2f ГБ · контекст %d%s",
             name[0] ? name : "Модель", desc, params_b, size_gb, s->n_ctx,
             s->mctx ? " · видит изображения" : "");
    return env->NewStringUTF(out);
}

JNIEXPORT void JNICALL
Java_com_neuropocket_app_engine_LlamaNative_resetChat(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s) clear_cache(s);
}

JNIEXPORT void JNICALL
Java_com_neuropocket_app_engine_LlamaNative_stop(JNIEnv *, jobject) {
    g_abort.store(true);
}

/**
 * Генерирует ответ ассистента для всей переписки.
 *
 * В тексте сообщений картинки обозначены маркером mediaMarker(); для каждого
 * маркера по порядку передаётся изображение RGB: images[i] (w[i]*h[i]*3 байт)
 * и его постоянный id (чтобы не кодировать одну картинку повторно).
 *
 * Уже обработанное начало переписки берётся из KV-кэша.
 * callback.onToken(byte[]) получает кусочки текста в UTF-8;
 * если он вернёт false — генерация останавливается.
 *
 * Возвращает число сгенерированных токенов или отрицательный код ошибки.
 */
JNIEXPORT jint JNICALL
Java_com_neuropocket_app_engine_LlamaNative_generate(
        JNIEnv *env, jobject, jlong handle,
        jobjectArray jroles, jobjectArray jcontents,
        jobjectArray jimages, jintArray jwidths, jintArray jheights, jobjectArray jids,
        jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty,
        jint maxTokens, jobject callback) {

    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return RES_BAD_HANDLE;
    g_abort.store(false);

    // --- 1. Переписка → текст по шаблону чата модели ---
    jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    for (jsize i = 0; i < n; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles[i] = jstring_to_std(env, r);
        contents[i] = jstring_to_std(env, c);
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    std::string prompt;
    if (!format_chat(s, roles, contents, prompt)) return RES_TEMPLATE_ERROR;

    // --- 2. Разбиваем на куски: текст и картинки ---
    std::vector<Piece> pieces;
    mtmd_input_chunks *chunks = nullptr;
    jsize n_images = jimages ? env->GetArrayLength(jimages) : 0;

    if (s->mctx) {
        std::vector<mtmd_bitmap *> bitmaps;
        std::vector<jint> ws(n_images), hs(n_images);
        if (n_images > 0) {
            env->GetIntArrayRegion(jwidths, 0, n_images, ws.data());
            env->GetIntArrayRegion(jheights, 0, n_images, hs.data());
        }
        for (jsize i = 0; i < n_images; ++i) {
            auto arr = (jbyteArray) env->GetObjectArrayElement(jimages, i);
            auto jid = (jstring) env->GetObjectArrayElement(jids, i);
            jsize len = env->GetArrayLength(arr);
            if (len != ws[i] * hs[i] * 3) {
                env->DeleteLocalRef(arr);
                env->DeleteLocalRef(jid);
                for (auto *b : bitmaps) mtmd_bitmap_free(b);
                return RES_MEDIA_ERROR;
            }
            jbyte *data = env->GetByteArrayElements(arr, nullptr);
            mtmd_bitmap *bmp = mtmd_bitmap_init((uint32_t) ws[i], (uint32_t) hs[i],
                                                reinterpret_cast<const unsigned char *>(data));
            env->ReleaseByteArrayElements(arr, data, JNI_ABORT);
            std::string id = jstring_to_std(env, jid);
            if (bmp && !id.empty()) mtmd_bitmap_set_id(bmp, id.c_str());
            env->DeleteLocalRef(arr);
            env->DeleteLocalRef(jid);
            if (!bmp) {
                for (auto *b : bitmaps) mtmd_bitmap_free(b);
                return RES_MEDIA_ERROR;
            }
            bitmaps.push_back(bmp);
        }

        mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
        chunks = mtmd_input_chunks_init();
        int32_t rc = mtmd_tokenize(s->mctx, chunks, &text,
                                   const_cast<const mtmd_bitmap **>(bitmaps.data()), bitmaps.size());
        for (auto *b : bitmaps) mtmd_bitmap_free(b);
        if (rc != 0) {
            LOGE("mtmd_tokenize вернул %d", rc);
            mtmd_input_chunks_free(chunks);
            return RES_MEDIA_ERROR;
        }
        for (size_t i = 0; i < mtmd_input_chunks_size(chunks); ++i) {
            const mtmd_input_chunk *ch = mtmd_input_chunks_get(chunks, i);
            Piece p;
            p.chunk = ch;
            if (mtmd_input_chunk_get_type(ch) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                size_t nt = 0;
                const llama_token *toks = mtmd_input_chunk_get_tokens_text(ch, &nt);
                if (nt == 0) continue;
                p.tokens.assign(toks, toks + nt);
                p.n_pos = (llama_pos) nt;
            } else {
                p.media = true;
                const char *id = mtmd_input_chunk_get_id(ch);
                p.id = id ? id : "";
                p.n_pos = mtmd_input_chunk_get_n_pos(ch);
            }
            pieces.push_back(std::move(p));
        }
    } else {
        if (n_images > 0) return RES_MEDIA_ERROR; // модель не видит картинки
        Piece p;
        p.tokens = tokenize(s->vocab, prompt);
        p.n_pos = (llama_pos) p.tokens.size();
        if (!p.tokens.empty()) pieces.push_back(std::move(p));
    }

    auto finish = [&](int code) {
        if (chunks) mtmd_input_chunks_free(chunks);
        for (auto &p : s->cached) p.chunk = nullptr;
        return code;
    };

    if (pieces.empty()) return finish(RES_TEMPLATE_ERROR);
    llama_pos total_pos = 0;
    for (auto &p : pieces) total_pos += p.n_pos;
    // Оставляем запас хотя бы на короткий ответ
    if (total_pos + 64 >= s->n_ctx) return finish(RES_CONTEXT_FULL);

    // --- 3. Ищем совпадающее начало с тем, что уже в KV-кэше ---
    size_t first = 0;       // первый кусок, который нужно прогнать
    size_t skip_tokens = 0; // сколько токенов этого куска уже в кэше
    llama_pos pos = 0;
    for (; first < pieces.size() && first < s->cached.size(); ++first) {
        const Piece &a = pieces[first];
        const Piece &b = s->cached[first];
        if (a.media != b.media) break;
        if (a.media) {
            if (!a.id.empty() && a.id == b.id && a.n_pos == b.n_pos) { pos += a.n_pos; continue; }
            break;
        }
        size_t k = 0;
        while (k < a.tokens.size() && k < b.tokens.size() && a.tokens[k] == b.tokens[k]) ++k;
        if (k == a.tokens.size() && k == b.tokens.size()) { pos += (llama_pos) k; continue; }
        skip_tokens = k;
        pos += (llama_pos) k;
        break;
    }
    if (first >= pieces.size()) {
        // Всё уже в кэше — перепрогоняем последний токен, чтобы получить логиты
        first = pieces.size() - 1;
        Piece &last = pieces[first];
        if (last.media) {
            skip_tokens = 0;
            pos -= last.n_pos;
        } else {
            skip_tokens = last.tokens.size() - 1;
            pos -= 1;
        }
    }
    if (skip_tokens > 0 && first < pieces.size() && skip_tokens >= pieces[first].tokens.size()) {
        skip_tokens = pieces[first].tokens.size() - 1;
    }

    llama_memory_t mem = llama_get_memory(s->ctx);
    if (pos < s->n_past) {
        if (!llama_memory_seq_rm(mem, 0, pos, -1)) {
            // Некоторые архитектуры (рекуррентные) не умеют частичное удаление
            llama_memory_clear(mem, true);
            first = 0;
            skip_tokens = 0;
            pos = 0;
        }
    }
    s->cached.clear();
    s->n_past = 0;

    // --- 4. Прогоняем новую часть промпта ---
    for (size_t i = first; i < pieces.size(); ++i) {
        Piece &p = pieces[i];
        bool last = i + 1 == pieces.size();
        int rc;
        if (p.media) {
            llama_pos new_pos = pos;
            rc = mtmd_helper_eval_chunk_single(s->mctx, s->ctx, p.chunk, pos, 0, s->n_batch, last, &new_pos);
            pos = new_pos;
        } else {
            size_t from = (i == first) ? skip_tokens : 0;
            rc = decode_tokens(s, p.tokens.data() + from, (int) (p.tokens.size() - from), pos, last);
            pos += (llama_pos) (p.tokens.size() - from);
        }
        if (rc != 0) {
            clear_cache(s);
            if (rc == 2 || g_abort.load()) return finish(0); // остановлено пользователем
            LOGE("Ошибка обработки промпта: %d", rc);
            return finish(rc == 1 ? RES_CONTEXT_FULL : RES_DECODE_ERROR);
        }
    }
    for (auto &p : pieces) p.chunk = nullptr;
    s->cached = pieces;
    s->n_past = pos;

    // --- 5. Сэмплер ---
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (topK > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        if (repeatPenalty > 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                    llama_vocab_n_tokens(s->vocab), 64, repeatPenalty, 0.0f, 0.0f));
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "([B)Z");

    // --- 6. Цикл генерации ---
    std::string pending;
    int generated = 0;
    int result = 0;
    auto emit = [&](bool flush_all) -> bool {
        size_t len = flush_all ? pending.size() : utf8_complete_prefix(pending);
        if (len == 0) return true;
        jbyteArray arr = env->NewByteArray((jsize) len);
        env->SetByteArrayRegion(arr, 0, (jsize) len, reinterpret_cast<const jbyte *>(pending.data()));
        jboolean keep = env->CallBooleanMethod(callback, onToken, arr);
        env->DeleteLocalRef(arr);
        pending.erase(0, len);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep == JNI_TRUE;
    };

    while (generated < maxTokens && !g_abort.load()) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;

        pending += token_to_piece(s->vocab, tok);
        ++generated;
        if (!emit(false)) break;

        if (s->n_past + 1 >= s->n_ctx) {
            result = RES_CONTEXT_FULL;
            break;
        }
        int rc = decode_tokens(s, &tok, 1, s->n_past, true);
        if (rc != 0) {
            clear_cache(s);
            if (rc != 2 && !g_abort.load()) {
                LOGE("llama_decode (gen) вернул %d", rc);
                result = RES_DECODE_ERROR;
            }
            break;
        }
        s->n_past += 1;
        if (!s->cached.empty() && !s->cached.back().media) {
            s->cached.back().tokens.push_back(tok);
            s->cached.back().n_pos += 1;
        } else {
            Piece p;
            p.tokens.push_back(tok);
            p.n_pos = 1;
            s->cached.push_back(std::move(p));
        }
    }
    emit(true);

    llama_sampler_free(smpl);
    env->DeleteLocalRef(cbCls);
    finish(0);
    return result == 0 ? generated : result;
}

} // extern "C"
