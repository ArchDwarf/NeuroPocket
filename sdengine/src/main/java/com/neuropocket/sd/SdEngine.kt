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
        System.loadLibrary("sdengine")
    }

    external fun systemInfo(): String
    external fun load(path: String, vaePath: String?, threads: Int, wtype: String): Long
    external fun free(handle: Long)
    external fun modelName(handle: Long): String
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
        image: ByteArray?,
        mask: ByteArray?,
        callback: SdProgress?,
        outSize: IntArray,
    ): ByteArray?
}

/** Как использовать исходное изображение. */
enum class SdMode(val code: Int) {
    /** Картинка по тексту. */
    TEXT(0),
    /** Перерисовать исходник по описанию (img2img); с маской — только выделенную область. */
    REDRAW(1),
    /** Изменить по инструкции («сделай зиму») — для edit-моделей вроде Instruct-Pix2Pix. */
    INSTRUCT(2),
}

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
    /** Исходник RGB размером width*height*3. */
    val image: ByteArray? = null,
    /** Маска width*height: 255 — перерисовать, 0 — оставить. */
    val mask: ByteArray? = null,
)

class SdImage(val width: Int, val height: Int, val rgb: ByteArray)

/**
 * Генератор изображений на stable-diffusion.cpp.
 * Все вызовы идут последовательно на отдельном потоке.
 */
class SdEngine {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(null, r, "sd-engine", 32L * 1024 * 1024) }
            .asCoroutineDispatcher()

    @Volatile private var handle = 0L

    val isLoaded: Boolean get() = handle != 0L

    suspend fun systemInfo(): String = withContext(dispatcher) { SdNative.systemInfo() }

    /** Загружает модель и возвращает название её архитектуры (например «SD 1.x»). */
    suspend fun load(path: String, threads: Int, weightType: String = "", vaePath: String? = null): String =
        withContext(dispatcher) {
            if (handle != 0L) {
                SdNative.free(handle)
                handle = 0L
            }
            val h = SdNative.load(path, vaePath, threads, weightType)
            if (h == 0L) {
                throw IllegalStateException(
                    "Не удалось загрузить модель. Поддерживаются однофайловые модели Stable Diffusion " +
                        "(.safetensors / .ckpt / .gguf). Также может не хватать памяти."
                )
            }
            handle = h
            SdNative.modelName(h)
        }

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
            req.sampler, req.mode.code, req.strength, req.imageCfg, req.image, req.mask, progress, size,
        ) ?: return@withContext null
        SdImage(size[0], size[1], rgb)
    }
}
