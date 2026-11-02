// JNI-мост к stable-diffusion.cpp: генерация, перерисовка (img2img),
// перерисовка по маске (inpaint) и редактирование по инструкции
// (Instruct-Pix2Pix и другие edit-модели).

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define TAG "NeuroPocketSD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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
    int prio = ANDROID_LOG_DEBUG;
    if (level == SD_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == SD_LOG_WARN) prio = ANDROID_LOG_WARN;
    else if (level == SD_LOG_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, TAG, text);
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

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
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

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_neuropocket_sd_SdNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(sd_get_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_neuropocket_sd_SdNative_load(JNIEnv *env, jobject, jstring jpath, jstring jvae,
                                      jint threads, jstring jwtype) {
    std::string path = jstr(env, jpath);
    std::string vae = jstr(env, jvae);
    std::string wtype = jstr(env, jwtype);

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path.c_str();
    if (!vae.empty()) p.vae_path = vae.c_str();
    p.n_threads = threads > 0 ? threads : sd_get_num_physical_cores();
    p.enable_mmap = true;
    p.diffusion_flash_attn = true; // меньше памяти на CPU
    if (!wtype.empty()) {
        sd_type_t t = str_to_sd_type(wtype.c_str());
        if (t != SD_TYPE_COUNT) p.wtype = t;
    }
    sd_ctx_t *ctx = new_sd_ctx(&p);
    if (!ctx) {
        LOGE("Не удалось загрузить модель %s", path.c_str());
        return 0;
    }
    if (!sd_ctx_supports_image_generation(ctx)) {
        LOGE("Модель не умеет генерировать изображения");
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
        jstring jsampler, jint mode, jfloat strength, jfloat imgCfg,
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

    {
        std::lock_guard<std::mutex> lock(g_progress_mutex);
        if (callback) {
            g_progress.callback = env->NewGlobalRef(callback);
            jclass cls = env->GetObjectClass(callback);
            g_progress.method = env->GetMethodID(cls, "onProgress", "(IIF)V");
            env->DeleteLocalRef(cls);
        }
    }

    sd_image_generation_result_t *result = nullptr;
    bool ok = generate_image(ctx, &p, &result);

    {
        std::lock_guard<std::mutex> lock(g_progress_mutex);
        if (g_progress.callback) env->DeleteGlobalRef(g_progress.callback);
        g_progress.callback = nullptr;
    }

    jbyteArray out = nullptr;
    if (ok && result && result->count > 0 && result->items[0].image.data) {
        sd_image_t &img = result->items[0].image;
        size_t n = (size_t) img.width * img.height * img.channel;
        std::vector<uint8_t> rgb;
        const uint8_t *data = img.data;
        if (img.channel != 3) {
            // Приводим к RGB
            rgb.resize((size_t) img.width * img.height * 3);
            for (size_t i = 0; i < (size_t) img.width * img.height; ++i) {
                for (int c = 0; c < 3; ++c) {
                    rgb[i * 3 + c] = img.data[i * img.channel + (img.channel == 1 ? 0 : c)];
                }
            }
            data = rgb.data();
            n = rgb.size();
        }
        out = env->NewByteArray((jsize) n);
        env->SetByteArrayRegion(out, 0, (jsize) n, reinterpret_cast<const jbyte *>(data));
        jint size[2] = {(jint) img.width, (jint) img.height};
        env->SetIntArrayRegion(outSize, 0, 2, size);
    } else {
        LOGE("Генерация не удалась или была отменена");
    }
    if (result) free_sd_image_generation_result(result);
    return out;
}

} // extern "C"
