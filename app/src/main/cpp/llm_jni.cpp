// JNI-мост между Kotlin и llama.cpp.
// Вся работа с моделью идёт на одном фоновом потоке (см. LlamaEngine.kt),
// только stop() может вызываться из любого потока.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define TAG "NeuroPocket"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Коды результата generate()
static constexpr int RES_TEMPLATE_ERROR = -1;
static constexpr int RES_CONTEXT_FULL   = -2;
static constexpr int RES_DECODE_ERROR   = -3;
static constexpr int RES_BAD_HANDLE     = -4;

static std::atomic<bool> g_abort{false};

struct Session {
    llama_model         *model   = nullptr;
    llama_context       *ctx     = nullptr;
    const llama_vocab   *vocab   = nullptr;
    std::string          tmpl;      // шаблон чата из метаданных модели
    std::vector<llama_token> cached; // токены, уже лежащие в KV-кэше
    int                  n_ctx   = 0;
    int                  n_batch = 0;
};

static void log_callback(ggml_log_level level, const char *text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, TAG, text);
}

static bool abort_callback(void *) { return g_abort.load(); }

static std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (!s) return {};
    // Берём байты через String.getBytes("UTF-8"), чтобы корректно
    // передать эмодзи и любые символы вне BMP.
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
    // Смотрим максимум 3 последних байта: незавершённый символ может быть только там.
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;              // байт продолжения
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

// Сбрасывает KV-кэш полностью.
static void clear_cache(Session *s) {
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_neuropocket_app_engine_LlamaNative_init(JNIEnv *env, jobject, jstring jlibDir) {
    llama_log_set(log_callback, nullptr);
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

JNIEXPORT jlong JNICALL
Java_com_neuropocket_app_engine_LlamaNative_load(JNIEnv *env, jobject, jstring jpath,
                                                 jint nCtx, jint nThreads) {
    std::string path = jstring_to_std(env, jpath);
    g_abort.store(false);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // всё считается на CPU телефона; веса читаются через mmap (по умолчанию)
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
    LOGI("Модель загружена, n_ctx=%d, шаблон: %s", s->n_ctx, s->tmpl.empty() ? "нет" : "есть");
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_neuropocket_app_engine_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return;
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
    snprintf(out, sizeof(out), "%s\n%s · %.2fB параметров · %.2f ГБ · контекст %d",
             name[0] ? name : "Модель", desc, params_b, size_gb, s->n_ctx);
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
 * Уже обработанное начало переписки берётся из KV-кэша, поэтому
 * каждое новое сообщение обрабатывается быстро.
 *
 * callback.onToken(byte[]) получает кусочки текста в UTF-8;
 * если он вернёт false — генерация останавливается.
 *
 * Возвращает число сгенерированных токенов или отрицательный код ошибки.
 */
JNIEXPORT jint JNICALL
Java_com_neuropocket_app_engine_LlamaNative_generate(
        JNIEnv *env, jobject, jlong handle,
        jobjectArray jroles, jobjectArray jcontents,
        jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty,
        jint maxTokens, jobject callback) {

    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return RES_BAD_HANDLE;
    g_abort.store(false);

    // --- 1. Собираем переписку и применяем шаблон чата модели ---
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

    std::vector<llama_token> tokens = tokenize(s->vocab, prompt);
    if (tokens.empty()) return RES_TEMPLATE_ERROR;
    // Оставляем запас хотя бы на короткий ответ
    if ((int) tokens.size() + 64 >= s->n_ctx) return RES_CONTEXT_FULL;

    // --- 2. Переиспользуем совпадающее начало из KV-кэша ---
    size_t common = 0;
    while (common < s->cached.size() && common < tokens.size() &&
           s->cached[common] == tokens[common]) {
        ++common;
    }
    if (common == tokens.size()) --common; // хотя бы один токен нужно прогнать для логитов

    llama_memory_t mem = llama_get_memory(s->ctx);
    if (common < s->cached.size()) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
            // Некоторые архитектуры (рекуррентные) не умеют частичное удаление
            llama_memory_clear(mem, true);
            common = 0;
        }
        s->cached.resize(common);
    }

    // --- 3. Обрабатываем новую часть промпта пакетами ---
    for (size_t i = common; i < tokens.size(); i += s->n_batch) {
        int32_t cnt = (int32_t) std::min<size_t>(s->n_batch, tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, cnt);
        int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            clear_cache(s);
            if (rc == 2 || g_abort.load()) return 0; // остановлено пользователем
            LOGE("llama_decode (prompt) вернул %d", rc);
            return rc == 1 ? RES_CONTEXT_FULL : RES_DECODE_ERROR;
        }
        s->cached.insert(s->cached.end(), tokens.begin() + i, tokens.begin() + i + cnt);
    }

    // --- 4. Сэмплер ---
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

    // --- 5. Цикл генерации ---
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

        if ((int) s->cached.size() + 1 >= s->n_ctx) {
            result = RES_CONTEXT_FULL;
            break;
        }
        llama_batch batch = llama_batch_get_one(&tok, 1);
        int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            clear_cache(s);
            if (rc != 2 && !g_abort.load()) {
                LOGE("llama_decode (gen) вернул %d", rc);
                result = RES_DECODE_ERROR;
            }
            break;
        }
        s->cached.push_back(tok);
    }
    emit(true);

    llama_sampler_free(smpl);
    env->DeleteLocalRef(cbCls);
    // Если контекст закончился посреди ответа — всё равно отдаём текст,
    // а Kotlin-сторона покажет предупреждение.
    return result == 0 ? generated : result;
}

} // extern "C"
