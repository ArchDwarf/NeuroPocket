// JNI-мост к stable-diffusion.cpp (Android и компьютер).
// Картинки: по тексту, перерисовка (img2img), перерисовка по маске,
// редактирование по инструкции (Pix2Pix, FLUX.2 klein, Qwen-Image-Edit и др.).
// Видео: Wan 2.x и другие видеомодели (текст → видео, картинка → видео).

#include <jni.h>

#include <atomic>
#include <cstdarg>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#ifdef __ANDROID__
#include <android/log.h>
#define TAG "NeuroPocketSD"
#define LOG_WRITE(prio, text) __android_log_write(prio, TAG, text)
#define PRIO_DEBUG ANDROID_LOG_DEBUG
#define PRIO_INFO ANDROID_LOG_INFO
#define PRIO_WARN ANDROID_LOG_WARN
#define PRIO_ERROR ANDROID_LOG_ERROR
#else
#define LOG_WRITE(prio, text) do { if ((prio) >= PRIO_INFO) { fputs(text, stderr); fflush(stderr); } } while (0)
#define PRIO_DEBUG 3
#define PRIO_INFO 4
#define PRIO_WARN 5
#define PRIO_ERROR 6
#endif

static void log_msg(int prio, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    std::string s(buf);
    s += '\n';
    LOG_WRITE(prio, s.c_str());
}

static JavaVM *g_vm = nullptr;

// Режимы работы с исходным изображением
static constexpr int MODE_TXT2IMG = 0;  // только текст
static constexpr int MODE_IMG2IMG = 1;  // перерисовать картинку (+ маска)
static constexpr int MODE_EDIT    = 2;  // редактирование по инструкции (edit-модели)

struct ProgressCtx {
    jobject callback = nullptr;  // global ref
    jmethodID method = nullptr;
};
static ProgressCtx g_progress;
static std::mutex g_progress_mutex;

static void log_cb(enum sd_log_level_t level, const char *text, void *) {
    int prio = PRIO_DEBUG;
    if (level == SD_LOG_ERROR) prio = PRIO_ERROR;
    else if (level == SD_LOG_WARN) prio = PRIO_WARN;
    else if (level == SD_LOG_INFO) prio = PRIO_INFO;
    LOG_WRITE(prio, text);
}

static void progress_cb(int step, int steps, float time, void *) {
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    if (!g_progress.callback || !g_vm) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else
        if (g_vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return;
#endif
        attached = true;
    }
    env->CallVoidMethod(g_progress.callback, g_progress.method, step, steps, time);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);
    return JNI_VERSION_1_6;
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static std::vector<uint8_t> jbytes(JNIEnv *env, jbyteArray a) {
    std::vector<uint8_t> out;
    if (!a) return out;
    jsize n = env->GetArrayLength(a);
    out.resize(n);
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

static void set_progress(JNIEnv *env, jobject callback) {
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    if (callback) {
        g_progress.callback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_progress.method = env->GetMethodID(cls, "onProgress", "(IIF)V");
        env->DeleteLocalRef(cls);
    }
}

static void clear_progress(JNIEnv *env) {
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    if (g_progress.callback) env->DeleteGlobalRef(g_progress.callback);
    g_progress.callback = nullptr;
}

// Приводит картинку результата к RGB
static std::vector<uint8_t> to_rgb(const sd_image_t &img) {
    size_t px = (size_t) img.width * img.height;
    std::vector<uint8_t> rgb(px * 3);
    if (img.channel == 3) {
        memcpy(rgb.data(), img.data, rgb.size());
    } else {
        for (size_t i = 0; i < px; ++i) {
            for (int c = 0; c < 3; ++c) {
                rgb[i * 3 + c] = img.data[i * img.channel + (img.channel == 1 ? 0 : c)];
            }
        }
    }
    return rgb;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_neuropocket_sd_SdNative_systemInfo(JNIEnv *env, jobject) {
    std::string info = sd_get_system_info();
    std::vector<char> buf(sd_list_devices(nullptr, 0) + 1);
    sd_list_devices(buf.data(), buf.size());
    info += "\nУстройства:\n";
    info += buf.data();
    return env->NewStringUTF(info.c_str());
}

/**
 * Загружает модель. keys/values — пути к компонентам и параметры:
 *   model, diffusion_model, high_noise_diffusion_model, vae, taesd, clip_l, clip_g,
 *   clip_vision, t5xxl, llm, llm_vision — пути к файлам;
 *   threads, wtype (f16/q8_0/q4_0…), backend (cpu / vulkan0 / пусто = авто),
 *   params_backend (cpu — держать веса в ОЗУ), max_vram (ГБ), flash_attn (auto/on/off),
 *   vae_tiling (1/0) — сохраняется и применяется при генерации.
 */
JNIEXPORT jlong JNICALL
Java_com_neuropocket_sd_SdNative_load(JNIEnv *env, jobject, jobjectArray jkeys, jobjectArray jvalues) {
    std::map<std::string, std::string> kv;
    jsize n = env->GetArrayLength(jkeys);
    for (jsize i = 0; i < n; ++i) {
        auto k = (jstring) env->GetObjectArrayElement(jkeys, i);
        auto v = (jstring) env->GetObjectArrayElement(jvalues, i);
        std::string value = jstr(env, v);
        if (!value.empty()) kv[jstr(env, k)] = value;
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(v);
    }
    auto get = [&](const char *key) -> const char * {
        auto it = kv.find(key);
        return it == kv.end() ? nullptr : it->second.c_str();
    };

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = get("model");
    p.diffusion_model_path = get("diffusion_model");
    p.high_noise_diffusion_model_path = get("high_noise_diffusion_model");
    p.vae_path = get("vae");
    p.taesd_path = get("taesd");
    p.clip_l_path = get("clip_l");
    p.clip_g_path = get("clip_g");
    p.clip_vision_path = get("clip_vision");
    p.t5xxl_path = get("t5xxl");
    p.llm_path = get("llm");
    p.llm_vision_path = get("llm_vision");
    p.enable_mmap = true;
    p.flash_attn_mode = get("flash_attn") ? get("flash_attn") : "auto";
    if (get("threads")) {
        int t = atoi(get("threads"));
        if (t > 0) p.n_threads = t;
    }
    if (get("wtype")) {
        sd_type_t t = str_to_sd_type(get("wtype"));
        if (t != SD_TYPE_COUNT) p.wtype = t;
    }
    if (get("backend")) p.backend = get("backend");
    if (get("params_backend")) p.params_backend = get("params_backend");
    if (get("max_vram")) p.max_vram = get("max_vram");

    if (!p.model_path && !p.diffusion_model_path) {
        log_msg(PRIO_ERROR, "Не указан файл модели");
        return 0;
    }
    sd_ctx_t *ctx = new_sd_ctx(&p);
    if (!ctx) {
        log_msg(PRIO_ERROR, "Не удалось загрузить модель");
        return 0;
    }
    if (!sd_ctx_supports_image_generation(ctx) && !sd_ctx_supports_video_generation(ctx)) {
        log_msg(PRIO_ERROR, "Модель не умеет генерировать изображения или видео");
        free_sd_ctx(ctx);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_neuropocket_sd_SdNative_free(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<sd_ctx_t *>(handle);
    if (ctx) free_sd_ctx(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_neuropocket_sd_SdNative_modelName(JNIEnv *env, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<sd_ctx_t *>(handle);
    return env->NewStringUTF(ctx ? sd_get_model_version_name(ctx) : "");
}

/** Возможности модели: бит 1 — картинки, бит 2 — видео. */
JNIEXPORT jint JNICALL
Java_com_neuropocket_sd_SdNative_capabilities(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<sd_ctx_t *>(handle);
    if (!ctx) return 0;
    return (sd_ctx_supports_image_generation(ctx) ? 1 : 0) | (sd_ctx_supports_video_generation(ctx) ? 2 : 0);
}

JNIEXPORT void JNICALL
Java_com_neuropocket_sd_SdNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<sd_ctx_t *>(handle);
    if (ctx) sd_cancel_generation(ctx, SD_CANCEL_ALL);
}

/**
 * Генерирует одно изображение.
 * image  — исходник RGB (width*height*3) или null;
 * mask   — маска в оттенках серого (width*height), белое = перерисовать; или null.
 * outSize[0..1] — ширина и высота результата.
 * Возвращает RGB-байты результата или null при ошибке/отмене.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_neuropocket_sd_SdNative_generate(
        JNIEnv *env, jobject, jlong handle,
        jstring jprompt, jstring jnegative,
        jint width, jint height, jint steps, jfloat cfg, jlong seed,
        jstring jsampler, jint mode, jfloat strength, jfloat imgCfg, jboolean vaeTiling,
        jbyteArray jimage, jbyteArray jmask,
        jobject callback, jintArray outSize) {

    auto *ctx = reinterpret_cast<sd_ctx_t *>(handle);
    if (!ctx) return nullptr;

    std::string prompt = jstr(env, jprompt);
    std::string negative = jstr(env, jnegative);
    std::string sampler = jstr(env, jsampler);
    std::vector<uint8_t> image = jbytes(env, jimage);
    std::vector<uint8_t> mask = jbytes(env, jmask);

    sd_img_gen_params_t p;
    sd_img_gen_params_init(&p);
    p.prompt = prompt.c_str();
    p.negative_prompt = negative.c_str();
    p.width = width;
    p.height = height;
    p.seed = seed;
    p.batch_count = 1;
    p.sample_params.sample_steps = steps;
    p.sample_params.guidance.txt_cfg = cfg;
    p.vae_tiling_params.enabled = vaeTiling == JNI_TRUE;
    if (!sampler.empty()) {
        sample_method_t m = str_to_sample_method(sampler.c_str());
        if (m != SAMPLE_METHOD_COUNT) p.sample_params.sample_method = m;
    }

    sd_image_t src{(uint32_t) width, (uint32_t) height, 3, nullptr};
    sd_image_t msk{(uint32_t) width, (uint32_t) height, 1, nullptr};
    bool has_image = image.size() == (size_t) width * height * 3;
    if (has_image) src.data = image.data();
    if (mask.size() == (size_t) width * height) msk.data = mask.data();

    if (has_image && mode == MODE_IMG2IMG) {
        p.init_image = src;
        p.strength = strength;
        if (msk.data) p.mask_image = msk;
    } else if (has_image && mode == MODE_EDIT) {
        p.ref_images = &src;
        p.ref_images_count = 1;
        if (imgCfg > 0) p.sample_params.guidance.img_cfg = imgCfg;
    }

    set_progress(env, callback);
    sd_image_generation_result_t *result = nullptr;
    bool ok = generate_image(ctx, &p, &result);
    clear_progress(env);

    jbyteArray out = nullptr;
    if (ok && result && result->count > 0 && result->items[0].image.data) {
        sd_image_t &img = result->items[0].image;
        std::vector<uint8_t> rgb = to_rgb(img);
        out = env->NewByteArray((jsize) rgb.size());
        env->SetByteArrayRegion(out, 0, (jsize) rgb.size(), reinterpret_cast<const jbyte *>(rgb.data()));
        jint size[2] = {(jint) img.width, (jint) img.height};
        env->SetIntArrayRegion(outSize, 0, 2, size);
    } else {
        log_msg(PRIO_ERROR, "Генерация не удалась или была отменена");
    }
    if (result) free_sd_image_generation_result(result);
    return out;
}

/**
 * Генерирует видео. image — первый кадр (картинка → видео) или null.
 * Возвращает все кадры подряд в RGB; outInfo = [ширина, высота, число кадров, fps].
 */
JNIEXPORT jbyteArray JNICALL
Java_com_neuropocket_sd_SdNative_generateVideo(
        JNIEnv *env, jobject, jlong handle,
        jstring jprompt, jstring jnegative,
        jint width, jint height, jint frames, jint fps, jint steps, jfloat cfg, jlong seed,
        jstring jsampler, jfloat flowShift, jint highNoiseSteps, jfloat highNoiseCfg, jboolean vaeTiling,
        jbyteArray jimage, jint imageW, jint imageH,
        jobject callback, jintArray outInfo) {

    auto *ctx = reinterpret_cast<sd_ctx_t *>(handle);
    if (!ctx) return nullptr;

    std::string prompt = jstr(env, jprompt);
    std::string negative = jstr(env, jnegative);
    std::string sampler = jstr(env, jsampler);
    std::vector<uint8_t> image = jbytes(env, jimage);

    sd_vid_gen_params_t p;
    sd_vid_gen_params_init(&p);
    p.prompt = prompt.c_str();
    p.negative_prompt = negative.c_str();
    p.width = width;
    p.height = height;
    p.video_frames = frames;
    p.fps = fps;
    p.seed = seed;
    p.sample_params.sample_steps = steps;
    p.sample_params.guidance.txt_cfg = cfg;
    if (flowShift > 0) p.sample_params.flow_shift = flowShift;
    if (highNoiseSteps > 0) {
        p.high_noise_sample_params.sample_steps = highNoiseSteps;
        p.high_noise_sample_params.guidance.txt_cfg = highNoiseCfg > 0 ? highNoiseCfg : cfg;
        if (flowShift > 0) p.high_noise_sample_params.flow_shift = flowShift;
    }
    p.vae_tiling_params.enabled = vaeTiling == JNI_TRUE;
    if (!sampler.empty()) {
        sample_method_t m = str_to_sample_method(sampler.c_str());
        if (m != SAMPLE_METHOD_COUNT) {
            p.sample_params.sample_method = m;
            p.high_noise_sample_params.sample_method = m;
        }
    }
    if (image.size() == (size_t) imageW * imageH * 3 && imageW > 0) {
        p.init_image = sd_image_t{(uint32_t) imageW, (uint32_t) imageH, 3, image.data()};
    }

    set_progress(env, callback);
    sd_video_generation_result_t *result = nullptr;
    bool ok = generate_video(ctx, &p, &result);
    clear_progress(env);

    jbyteArray out = nullptr;
    if (ok && result && result->frame_count > 0 && result->frames[0].data) {
        const sd_image_t &first = result->frames[0];
        size_t frame_bytes = (size_t) first.width * first.height * 3;
        size_t total = frame_bytes * result->frame_count;
        out = env->NewByteArray((jsize) total);
        if (out) {
            for (int i = 0; i < result->frame_count; ++i) {
                std::vector<uint8_t> rgb = to_rgb(result->frames[i]);
                env->SetByteArrayRegion(out, (jsize) (i * frame_bytes), (jsize) frame_bytes,
                                        reinterpret_cast<const jbyte *>(rgb.data()));
            }
            jint info[4] = {(jint) first.width, (jint) first.height, result->frame_count,
                            result->fps > 0 ? result->fps : fps};
            env->SetIntArrayRegion(outInfo, 0, 4, info);
        }
    } else {
        log_msg(PRIO_ERROR, "Генерация видео не удалась или была отменена");
    }
    if (result) free_sd_video_generation_result(result);
    return out;
}

} // extern "C"
