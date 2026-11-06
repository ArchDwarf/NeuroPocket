package com.neuropocket.sd

import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@Keep
fun interface SdProgress {
    fun onProgress(step: Int, steps: Int, secondsPerStep: Float)
}

@Keep
internal object SdNative {
    init {
        // Android: System.loadLibrary; компьютер: версия для видеокарты или для процессора
        SdLibrary.load()
    }

    external fun systemInfo(): String
    external fun listDevices(): String
    external fun load(keys: Array<String>, values: Array<String>): Long
    external fun free(handle: Long)
    external fun modelName(handle: Long): String
    external fun capabilities(handle: Long): Int
    external fun cancel(handle: Long)
    external fun generate(
        handle: Long,
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        sampler: String,
        mode: Int,
        strength: Float,
        imgCfg: Float,
        vaeTiling: Boolean,
        image: ByteArray?,
        mask: ByteArray?,
        callback: SdProgress?,
        outSize: IntArray,
    ): ByteArray?

    external fun generateVideo(
        handle: Long,
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        frames: Int,
        fps: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        sampler: String,
        flowShift: Float,
        highNoiseSteps: Int,
        highNoiseCfg: Float,
        vaeTiling: Boolean,
        image: ByteArray?,
        imageWidth: Int,
        imageHeight: Int,
        callback: SdProgress?,
        outInfo: IntArray,
    ): ByteArray?
}

/** Как использовать исходное изображение. */
enum class SdMode(val code: Int) {
    /** Картинка по тексту. */
    TEXT(0),
    /** Перерисовать исходник по описанию (img2img); с маской — только выделенную область. */
    REDRAW(1),
    /** Изменить по инструкции («сделай зиму») — для edit-моделей. */
    INSTRUCT(2),
}

/**
 * Файлы модели. Простые модели — один файл [model]; большие (FLUX, SD3,
 * Qwen-Image, Z-Image, Wan…) собираются из нескольких компонентов.
 */
data class SdModelFiles(
    val model: String? = null,
    val diffusionModel: String? = null,
    val highNoiseDiffusionModel: String? = null,
    val vae: String? = null,
    val taesd: String? = null,
    val clipL: String? = null,
    val clipG: String? = null,
    val clipVision: String? = null,
    val t5xxl: String? = null,
    val llm: String? = null,
    val llmVision: String? = null,
) {
    fun toMap(): Map<String, String?> = mapOf(
        "model" to model,
        "diffusion_model" to diffusionModel,
        "high_noise_diffusion_model" to highNoiseDiffusionModel,
        "vae" to vae,
        "taesd" to taesd,
        "clip_l" to clipL,
        "clip_g" to clipG,
        "clip_vision" to clipVision,
        "t5xxl" to t5xxl,
        "llm" to llm,
        "llm_vision" to llmVision,
    )

    val mainFile: String? get() = model ?: diffusionModel
}

/** Параметры загрузки. */
data class SdLoadOptions(
    val threads: Int = 0,
    /** "" — как в файле, иначе f16 / q8_0 / q4_0 … */
    val weightType: String = "",
    /** "" — автоматически (видеокарта, если есть), "cpu" — только процессор. */
    val backend: String = "",
    /** "cpu" — держать веса в оперативной памяти (для видеокарт с малым объёмом памяти). */
    val paramsBackend: String = "",
    /** Ограничение видеопамяти в ГБ ("" — без ограничения). */
    val maxVramGb: String = "",
    /** Как делить модуль между несколькими видеокартами: layer / row ("" — по умолчанию). */
    val splitMode: String = "",
)

data class SdModelInfo(val arch: String, val canImage: Boolean, val canVideo: Boolean)

data class SdRequest(
    val prompt: String,
    val negative: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfg: Float = 7f,
    val seed: Long = -1,
    /** "" — по умолчанию для модели; иначе euler_a, euler, dpm++2m, lcm … */
    val sampler: String = "",
    val mode: SdMode = SdMode.TEXT,
    /** Насколько сильно менять исходник в режиме REDRAW (0..1). */
    val strength: Float = 0.6f,
    /** Насколько держаться исходника в режиме INSTRUCT (обычно 1.5). */
    val imageCfg: Float = 1.5f,
    /** Обрабатывать изображение по частям — меньше памяти для больших размеров. */
    val vaeTiling: Boolean = false,
    /** Исходник RGB размером width*height*3. */
    val image: ByteArray? = null,
    /** Маска width*height: 255 — перерисовать, 0 — оставить. */
    val mask: ByteArray? = null,
)

data class SdVideoRequest(
    val prompt: String,
    val negative: String = "",
    val width: Int = 832,
    val height: Int = 480,
    val frames: Int = 33,
    val fps: Int = 16,
    val steps: Int = 20,
    val cfg: Float = 6f,
    val seed: Long = -1,
    val sampler: String = "euler",
    /** 0 — по умолчанию для модели. */
    val flowShift: Float = 0f,
    /** Шаги второй модели (Wan 2.2 A14B: high noise), 0 — не используется. */
    val highNoiseSteps: Int = 0,
    val highNoiseCfg: Float = 0f,
    val vaeTiling: Boolean = false,
    /** Первый кадр (картинка → видео), RGB imageWidth*imageHeight*3. */
    val image: ByteArray? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
)

class SdImage(val width: Int, val height: Int, val rgb: ByteArray)

class SdVideo(val width: Int, val height: Int, val fps: Int, val frames: List<ByteArray>)

/**
 * Генератор изображений и видео на stable-diffusion.cpp.
 * Все вызовы идут последовательно на отдельном потоке.
 */
class SdEngine {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(null, r, "sd-engine", 64L * 1024 * 1024) }
            .asCoroutineDispatcher()

    @Volatile private var handle = 0L

    val isLoaded: Boolean get() = handle != 0L

    suspend fun systemInfo(): String = withContext(dispatcher) { SdNative.systemInfo() }

    /** Устройства, которые видит stable-diffusion.cpp: пары (имя, описание), например ("Vulkan0", "NVIDIA …"). */
    suspend fun listDevices(): List<Pair<String, String>> = withContext(dispatcher) {
        SdNative.listDevices().lines().filter { it.isNotBlank() }.map { l ->
            l.substringBefore('\t') to l.substringAfter('\t', "")
        }
    }

    /** Загружает модель из одного или нескольких файлов. */
    suspend fun load(files: SdModelFiles, options: SdLoadOptions = SdLoadOptions()): SdModelInfo =
        withContext(dispatcher) {
            if (handle != 0L) {
                SdNative.free(handle)
                handle = 0L
            }
            val kv = LinkedHashMap<String, String>()
            files.toMap().forEach { (k, v) -> if (!v.isNullOrBlank()) kv[k] = v }
            if (options.threads > 0) kv["threads"] = options.threads.toString()
            if (options.weightType.isNotBlank()) kv["wtype"] = options.weightType
            if (options.backend.isNotBlank()) kv["backend"] = options.backend
            if (options.paramsBackend.isNotBlank()) kv["params_backend"] = options.paramsBackend
            if (options.maxVramGb.isNotBlank()) kv["max_vram"] = options.maxVramGb
            if (options.splitMode.isNotBlank()) kv["split_mode"] = options.splitMode
            val h = SdNative.load(kv.keys.toTypedArray(), kv.values.toTypedArray())
            if (h == 0L) {
                throw IllegalStateException(
                    "Не удалось загрузить модель. Проверьте, что выбраны все нужные файлы " +
                        "(для больших моделей: VAE и текстовые энкодеры), и что хватает памяти."
                )
            }
            handle = h
            val caps = SdNative.capabilities(h)
            SdModelInfo(SdNative.modelName(h), caps and 1 != 0, caps and 2 != 0)
        }

    /** Упрощённая загрузка однофайловой модели (как раньше на телефоне). */
    suspend fun load(path: String, threads: Int, weightType: String = ""): String =
        load(SdModelFiles(model = path), SdLoadOptions(threads = threads, weightType = weightType)).arch

    suspend fun unload() = withContext(dispatcher) {
        if (handle != 0L) {
            SdNative.free(handle)
            handle = 0L
        }
    }

    fun cancel() {
        val h = handle
        if (h != 0L) SdNative.cancel(h)
    }

    suspend fun generate(req: SdRequest, progress: SdProgress?): SdImage? = withContext(dispatcher) {
        val h = handle
        check(h != 0L) { "Модель не загружена" }
        val size = IntArray(2)
        val rgb = SdNative.generate(
            h, req.prompt, req.negative, req.width, req.height, req.steps, req.cfg, req.seed,
            req.sampler, req.mode.code, req.strength, req.imageCfg, req.vaeTiling, req.image, req.mask,
            progress, size,
        ) ?: return@withContext null
        SdImage(size[0], size[1], rgb)
    }

    suspend fun generateVideo(req: SdVideoRequest, progress: SdProgress?): SdVideo? = withContext(dispatcher) {
        val h = handle
        check(h != 0L) { "Модель не загружена" }
        val info = IntArray(4)
        val all = SdNative.generateVideo(
            h, req.prompt, req.negative, req.width, req.height, req.frames, req.fps, req.steps, req.cfg,
            req.seed, req.sampler, req.flowShift, req.highNoiseSteps, req.highNoiseCfg, req.vaeTiling,
            req.image, req.imageWidth, req.imageHeight, progress, info,
        ) ?: return@withContext null
        val (w, hgt, count, fps) = info
        val frameBytes = w * hgt * 3
        val frames = List(count) { i -> all.copyOfRange(i * frameBytes, (i + 1) * frameBytes) }
        SdVideo(w, hgt, fps, frames)
    }
}
