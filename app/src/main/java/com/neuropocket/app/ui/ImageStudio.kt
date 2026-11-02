package com.neuropocket.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neuropocket.app.data.LocalModel
import com.neuropocket.app.data.Media
import com.neuropocket.app.data.Settings
import com.neuropocket.sd.SdEngine
import com.neuropocket.sd.SdMode
import com.neuropocket.sd.SdProgress
import com.neuropocket.sd.SdRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

sealed interface SdState {
    data object None : SdState
    data class Loading(val name: String) : SdState
    data class Ready(val name: String, val arch: String) : SdState
    data class Error(val name: String, val message: String) : SdState
}

/** Мазок кисти маски: точки в долях от размера картинки (0..1). */
class MaskStroke(val points: List<Pair<Float, Float>>, val radius: Float)

enum class Aspect(val title: String, val w: Int, val h: Int) {
    SQUARE("1:1", 1, 1), PORTRAIT("3:4", 3, 4), LANDSCAPE("4:3", 4, 3)
}

class GenResult(val bitmap: Bitmap, val prompt: String, val seed: Long, val seconds: Float)

/**
 * Генерация и редактирование картинок на устройстве (stable-diffusion.cpp).
 */
class ImageStudio(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val saveSettings: (Settings) -> Unit,
    private val toast: (String) -> Unit,
    private val beforeLoad: suspend () -> Unit,
) {
    private val engine = SdEngine()

    var state by mutableStateOf<SdState>(SdState.None); private set
    val isModelLoaded: Boolean get() = engine.isLoaded

    // ----- Параметры -----
    var prompt by mutableStateOf("")
    var negative by mutableStateOf("blurry, low quality, deformed, bad anatomy, extra fingers, text, watermark")
    var mode by mutableStateOf(SdMode.TEXT)
    var steps by mutableStateOf(20)
    var cfg by mutableStateOf(7f)
    var strength by mutableStateOf(0.6f)
    var imageCfg by mutableStateOf(1.5f)
    var side by mutableStateOf(512)
    var aspect by mutableStateOf(Aspect.SQUARE)
    var sampler by mutableStateOf("")
    var fixedSeed by mutableStateOf<Long?>(null)

    // ----- Исходник и маска -----
    var source by mutableStateOf<Bitmap?>(null); private set
    val maskStrokes = mutableStateListOf<MaskStroke>()
    var brushSize by mutableStateOf(0.06f)

    // ----- Результат -----
    var generating by mutableStateOf(false); private set
    var step by mutableStateOf(0); private set
    var totalSteps by mutableStateOf(0); private set
    var secondsPerStep by mutableStateOf(0f); private set
    val results = mutableStateListOf<GenResult>()
    var current by mutableStateOf<GenResult?>(null)
    private var job: Job? = null

    init {
        val last = settings().imageModelPath
        if (last.isNotBlank() && File(last).exists()) {
            // Не загружаем сразу: модель картинок занимает много памяти,
            // загрузим при первом открытии вкладки
            state = SdState.None
        }
    }

    /** Загрузить последнюю модель, если она ещё не загружена. */
    fun ensureLoaded() {
        if (state !is SdState.None) return
        val last = settings().imageModelPath
        if (last.isNotBlank() && File(last).exists()) load(last)
    }

    fun select(model: LocalModel) {
        saveSettings(settings().copy(imageModelPath = model.path))
        load(model.path)
    }

    fun reload() {
        settings().imageModelPath.takeIf { it.isNotBlank() }?.let { load(it) }
    }

    private fun load(path: String) {
        val name = File(path).name
        scope.launch {
            cancel()
            job?.join()
            beforeLoad()
            state = SdState.Loading(name)
            state = try {
                val s = settings()
                val arch = engine.load(path, s.threads, s.imageWeightType)
                applyDefaults(arch, name)
                SdState.Ready(name, arch)
            } catch (e: Throwable) {
                SdState.Error(name, e.message ?: e.toString())
            }
        }
    }

    /** Подбирает настройки под тип модели. */
    private fun applyDefaults(arch: String, fileName: String) {
        val f = fileName.lowercase()
        when {
            arch.startsWith("SDXS") -> { steps = 1; cfg = 1f; side = 512; sampler = "" }
            "turbo" in f || "lightning" in f || "lcm" in f -> { steps = 4; cfg = 1f; side = 512; sampler = "euler_a" }
            arch.startsWith("SDXL") -> { steps = 20; cfg = 6f; side = 768; sampler = "euler_a" }
            else -> { steps = 20; cfg = 7f; side = 512; sampler = "euler_a" }
        }
        when {
            "Pix2Pix" in arch -> { mode = SdMode.INSTRUCT; imageCfg = 1.5f }
            mode == SdMode.INSTRUCT -> mode = if (source != null) SdMode.REDRAW else SdMode.TEXT
        }
    }

    val isEditModel: Boolean get() = (state as? SdState.Ready)?.arch?.let { a ->
        "Pix2Pix" in a || a.startsWith("Flux.2") || a.startsWith("Flux.3") || a.startsWith("Qwen Image") ||
            a.startsWith("Longcat") || a.startsWith("Boogu")
    } == true

    suspend fun unload() {
        engine.unload()
        state = SdState.None
    }

    fun onModelDeleted(model: LocalModel) {
        if (settings().imageModelPath == model.path) {
            scope.launch {
                cancel()
                job?.join()
                unload()
            }
            saveSettings(settings().copy(imageModelPath = ""))
        }
    }

    // ===================== Исходник =====================

    fun setSource(uri: Uri) {
        scope.launch {
            try {
                source = Media.loadBitmap(app, uri, 2048)
                maskStrokes.clear()
                if (mode == SdMode.TEXT) mode = if (isEditModel) SdMode.INSTRUCT else SdMode.REDRAW
            } catch (e: Exception) {
                toast("Не удалось открыть изображение: ${e.message}")
            }
        }
    }

    fun useAsSource(bmp: Bitmap) {
        source = bmp
        maskStrokes.clear()
        if (mode == SdMode.TEXT) mode = if (isEditModel) SdMode.INSTRUCT else SdMode.REDRAW
    }

    fun clearSource() {
        source = null
        maskStrokes.clear()
        mode = SdMode.TEXT
    }

    /** Рисует маску в нужном размере: белое — перерисовать. */
    private fun renderMask(w: Int, h: Int): Bitmap? {
        if (maskStrokes.isEmpty()) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val p = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val dim = maxOf(w, h)
        for (s in maskStrokes) {
            p.strokeWidth = s.radius * 2 * dim
            if (s.points.size == 1) {
                val (x, y) = s.points[0]
                p.style = Paint.Style.FILL
                c.drawCircle(x * w, y * h, s.radius * dim, p)
                p.style = Paint.Style.STROKE
            } else {
                for (i in 1 until s.points.size) {
                    val (x0, y0) = s.points[i - 1]
                    val (x1, y1) = s.points[i]
                    c.drawLine(x0 * w, y0 * h, x1 * w, y1 * h, p)
                }
            }
        }
        return bmp
    }

    // ===================== Генерация =====================

    fun generate() {
        if (generating) return
        if (state !is SdState.Ready) {
            toast("Сначала выберите модель для картинок")
            return
        }
        if (prompt.isBlank()) {
            toast(if (mode == SdMode.INSTRUCT) "Опишите, что изменить" else "Опишите картинку")
            return
        }
        val src = source
        if (mode != SdMode.TEXT && src == null) {
            toast("Добавьте исходное фото")
            return
        }
        generating = true
        step = 0
        totalSteps = steps
        job = scope.launch {
            val started = System.currentTimeMillis()
            try {
                val req = withContext(Dispatchers.Default) { buildRequest(src) }
                val img = engine.generate(req, SdProgress { st, total, sec ->
                    step = st
                    totalSteps = total
                    secondsPerStep = sec
                })
                if (img == null) {
                    toast("Генерация остановлена или не удалась")
                } else {
                    val bmp = withContext(Dispatchers.Default) { Media.fromRgb(img.rgb, img.width, img.height) }
                    val r = GenResult(bmp, prompt, req.seed, (System.currentTimeMillis() - started) / 1000f)
                    results.add(0, r)
                    if (results.size > 12) results.removeAt(results.lastIndex)
                    current = r
                }
            } catch (e: Throwable) {
                toast("Ошибка: ${e.message}")
            } finally {
                generating = false
            }
        }
    }

    private fun buildRequest(src: Bitmap?): SdRequest {
        val seed = fixedSeed ?: Random.nextLong(0, Int.MAX_VALUE.toLong())
        var w: Int
        var h: Int
        var image: ByteArray? = null
        var mask: ByteArray? = null
        if (mode != SdMode.TEXT && src != null) {
            val fitted = Media.fitForDiffusion(src, side)
            w = fitted.width
            h = fitted.height
            image = Media.toRgb(fitted)
            if (mode == SdMode.REDRAW) renderMask(w, h)?.let { mask = Media.toGray(it) }
        } else {
            val k = side.toFloat() / maxOf(aspect.w, aspect.h)
            w = ((aspect.w * k / 64f).toInt() * 64).coerceAtLeast(64)
            h = ((aspect.h * k / 64f).toInt() * 64).coerceAtLeast(64)
        }
        return SdRequest(
            prompt = prompt.trim(),
            negative = if (cfg > 1f) negative.trim() else "",
            width = w,
            height = h,
            steps = steps,
            cfg = cfg,
            seed = seed,
            sampler = sampler,
            mode = mode,
            // С маской перерисовываем выделенное сильнее
            strength = if (mask != null) maxOf(strength, 0.75f) else strength,
            imageCfg = imageCfg,
            image = image,
            mask = mask,
        )
    }

    fun cancel() {
        if (generating) engine.cancel()
    }

    fun save(bmp: Bitmap) {
        scope.launch {
            val uri = runCatching { Media.saveToGallery(app, bmp) }.getOrNull()
            toast(if (uri != null) "Сохранено в галерею: Pictures/NeuroPocket" else "Не удалось сохранить")
        }
    }
}
