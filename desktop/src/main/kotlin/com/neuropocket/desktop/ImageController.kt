package com.neuropocket.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.neuropocket.sd.SdEngine
import com.neuropocket.sd.SdLibrary
import com.neuropocket.sd.SdLoadOptions
import com.neuropocket.sd.SdMode
import com.neuropocket.sd.SdProgress
import com.neuropocket.sd.SdRequest
import com.neuropocket.sd.SdVideoRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.random.Random

sealed interface SdLoadState {
    data object None : SdLoadState
    data class Loading(val name: String) : SdLoadState
    data class Ready(val name: String, val arch: String, val canImage: Boolean, val canVideo: Boolean) : SdLoadState
    data class Error(val name: String, val message: String) : SdLoadState
}

/** Мазок маски: точки в долях от размера картинки. */
class Stroke(val points: List<Pair<Float, Float>>, val radius: Float)

class ImageResult(val image: BufferedImage, val bitmap: ImageBitmap, val file: File, val seed: Long, val seconds: Float)

class VideoResult(val frames: List<ImageBitmap>, val fps: Int, val file: File, val seconds: Float)

/**
 * Генерация картинок (video = false) или видео (video = true) на stable-diffusion.cpp.
 * У каждого — свой движок, чтобы можно было держать обе модели (если хватает памяти).
 */
class ImageController(private val app: AppState, val video: Boolean) {
    private val engine = SdEngine()

    var state by mutableStateOf<SdLoadState>(SdLoadState.None); private set
    var profile by mutableStateOf<SdProfile?>(null); private set

    // ----- Параметры -----
    var prompt by mutableStateOf("")
    var negative by mutableStateOf("")
    var mode by mutableStateOf(SdMode.TEXT)
    var steps by mutableStateOf(20)
    var cfg by mutableStateOf(7f)
    var strength by mutableStateOf(0.65f)
    var imageCfg by mutableStateOf(1.5f)
    var width by mutableStateOf(512)
    var height by mutableStateOf(512)
    var sampler by mutableStateOf("")
    var fixedSeed by mutableStateOf<Long?>(null)
    var frames by mutableStateOf(33)
    var fps by mutableStateOf(16)
    var flowShift by mutableStateOf(0f)
    var highNoiseSteps by mutableStateOf(0)

    // ----- Исходник и маска -----
    var source by mutableStateOf<BufferedImage?>(null); private set
    var sourceBitmap by mutableStateOf<ImageBitmap?>(null); private set
    val strokes = mutableStateListOf<Stroke>()
    var brush by mutableStateOf(0.05f)

    // ----- Ход генерации -----
    var running by mutableStateOf(false); private set
    var step by mutableStateOf(0); private set
    var totalSteps by mutableStateOf(0); private set
    var secPerStep by mutableStateOf(0f); private set
    var phase by mutableStateOf(""); private set
    val results = mutableStateListOf<ImageResult>()
    var current by mutableStateOf<ImageResult?>(null)
    var videoResult by mutableStateOf<VideoResult?>(null)
    private var job: Job? = null

    val profiles: List<SdProfile> get() = app.profiles.filter { it.video == video }
    val canEdit: Boolean get() = profile?.canEdit == true ||
        (state as? SdLoadState.Ready)?.arch?.let { "Pix2Pix" in it || it.startsWith("Flux.2") || "Edit" in it } == true

    fun ensureLoaded() {
        if (state !is SdLoadState.None) return
        val name = if (video) app.settings.videoProfile else app.settings.imageProfile
        profiles.find { it.name == name }?.let { load(it) }
    }

    fun select(p: SdProfile) {
        app.updateSettings(if (video) app.settings.copy(videoProfile = p.name) else app.settings.copy(imageProfile = p.name))
        load(p)
    }

    fun reload() {
        profile?.let { load(it) }
    }

    private fun load(p: SdProfile) {
        val missing = p.files.toMap().values.filterNotNull().filter { !File(it).exists() }
        if (missing.isNotEmpty()) {
            state = SdLoadState.Error(p.name, "Не все файлы скачаны:\n" + missing.joinToString("\n") { File(it).name } +
                "\n\nДождитесь окончания загрузок во вкладке «Модели».")
            profile = p
            return
        }
        app.scope.launch {
            cancel()
            job?.join()
            // Большие модели картинок и видео вместе в видеопамять не помещаются
            val other = if (video) app.images else app.video
            if (other.isLoaded) {
                other.unload()
                app.toast("Модель ${if (video) "картинок" else "видео"} выгружена, чтобы освободить видеопамять")
            }
            state = SdLoadState.Loading(p.name)
            state = try {
                val s = app.settings
                val info = engine.load(
                    p.files,
                    SdLoadOptions(
                        threads = s.threads,
                        weightType = s.sdWeightType,
                        backend = if (s.useGpu) "" else "cpu",
                        paramsBackend = if (s.sdOffloadToCpu) "cpu" else "",
                        maxVramGb = s.sdMaxVram,
                    ),
                )
                profile = p
                applyDefaults(p)
                SdLoadState.Ready(p.name, info.arch, info.canImage, info.canVideo)
            } catch (e: Throwable) {
                SdLoadState.Error(p.name, e.message ?: e.toString())
            }
        }
    }

    private fun applyDefaults(p: SdProfile) {
        steps = p.steps
        cfg = p.cfg
        sampler = p.sampler
        width = p.width
        height = p.height
        frames = p.frames
        fps = p.fps
        flowShift = p.flowShift
        highNoiseSteps = p.highNoiseSteps
        if (p.negative.isNotBlank()) negative = p.negative
        if (mode == SdMode.INSTRUCT && !canEdit) mode = if (source != null) SdMode.REDRAW else SdMode.TEXT
    }

    fun backendName(): String = SdLibrary.loadedVariant

    // ===================== Исходник =====================

    fun chooseSource() {
        val f = DesktopMedia.chooseFile("Исходное изображение", DesktopMedia.IMAGE_EXT, "Изображения") ?: return
        app.scope.launch {
            try {
                useSource(DesktopMedia.loadImage(f))
            } catch (e: Exception) {
                app.toast(e.message ?: "Ошибка")
            }
        }
    }

    fun chooseVideoFrame(onPicked: (File, Double) -> Unit) {
        val f = DesktopMedia.chooseFile("Видео", DesktopMedia.VIDEO_EXT, "Видео MP4") ?: return
        app.scope.launch {
            try {
                onPicked(f, DesktopMedia.videoDuration(f))
            } catch (e: Exception) {
                app.toast("Видео не поддерживается (нужен MP4 с H.264): ${e.message}")
            }
        }
    }

    fun useSource(img: BufferedImage) {
        val rgb = DesktopMedia.resize(img, img.width, img.height)
        source = rgb
        sourceBitmap = DesktopMedia.scaleToMax(rgb, 1024).toComposeImageBitmap()
        strokes.clear()
        if (!video && mode == SdMode.TEXT) mode = if (canEdit) SdMode.INSTRUCT else SdMode.REDRAW
    }

    fun clearSource() {
        source = null
        sourceBitmap = null
        strokes.clear()
        mode = SdMode.TEXT
    }

    private fun renderMask(w: Int, h: Int): BufferedImage? {
        if (strokes.isEmpty()) return null
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.BLACK
        g.fillRect(0, 0, w, h)
        g.color = Color.WHITE
        val dim = maxOf(w, h)
        for (s in strokes) {
            val r = s.radius * dim
            g.stroke = BasicStroke(r * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (s.points.size == 1) {
                val (x, y) = s.points[0]
                g.fillOval((x * w - r).toInt(), (y * h - r).toInt(), (2 * r).toInt(), (2 * r).toInt())
            } else {
                for (i in 1 until s.points.size) {
                    val (x0, y0) = s.points[i - 1]
                    val (x1, y1) = s.points[i]
                    g.drawLine((x0 * w).toInt(), (y0 * h).toInt(), (x1 * w).toInt(), (y1 * h).toInt())
                }
            }
        }
        g.dispose()
        return img
    }

    // ===================== Генерация =====================

    private val progress = SdProgress { s, total, sec ->
        step = s
        totalSteps = total
        secPerStep = sec
        phase = "Генерация"
    }

    fun generate() {
        if (running) return
        if (state !is SdLoadState.Ready) {
            app.toast("Сначала выберите модель")
            return
        }
        if (prompt.isBlank()) {
            app.toast("Опишите, что нужно получить")
            return
        }
        if (!video && mode != SdMode.TEXT && source == null) {
            app.toast("Добавьте исходное изображение")
            return
        }
        running = true
        step = 0
        totalSteps = steps + (if (video) highNoiseSteps else 0)
        phase = "Подготовка"
        val seed = fixedSeed ?: Random.nextLong(0, Int.MAX_VALUE.toLong())
        job = app.scope.launch {
            val started = System.currentTimeMillis()
            try {
                if (video) runVideo(seed, started) else runImage(seed, started)
            } catch (e: Throwable) {
                app.toast("Ошибка: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())

    private suspend fun runImage(seed: Long, started: Long) {
        val src = source
        val req = withContext(Dispatchers.Default) {
            var w = width / 64 * 64
            var h = height / 64 * 64
            var image: ByteArray? = null
            var mask: ByteArray? = null
            if (mode != SdMode.TEXT && src != null) {
                val fitted = DesktopMedia.fitForDiffusion(src, maxOf(width, height))
                w = fitted.width
                h = fitted.height
                image = DesktopMedia.toRgb(fitted)
                if (mode == SdMode.REDRAW) renderMask(w, h)?.let { mask = DesktopMedia.toGray(it) }
            }
            SdRequest(
                prompt = prompt.trim(), negative = if (cfg > 1f) negative.trim() else "",
                width = w, height = h, steps = steps, cfg = cfg, seed = seed, sampler = sampler, mode = mode,
                strength = if (mask != null) maxOf(strength, 0.75f) else strength, imageCfg = imageCfg,
                vaeTiling = app.settings.sdVaeTiling, image = image, mask = mask,
            )
        }
        val img = engine.generate(req, progress)
        if (img == null) {
            app.toast("Генерация остановлена или не удалась")
            return
        }
        val bi = withContext(Dispatchers.Default) { DesktopMedia.fromRgb(img.rgb, img.width, img.height) }
        val file = File(Paths.outputs, "images/${stamp()}_$seed.png")
        withContext(Dispatchers.IO) { DesktopMedia.savePng(bi, file) }
        val r = ImageResult(bi, bi.toComposeImageBitmap(), file, seed, (System.currentTimeMillis() - started) / 1000f)
        results.add(0, r)
        if (results.size > 30) results.removeAt(results.lastIndex)
        current = r
    }

    private suspend fun runVideo(seed: Long, started: Long) {
        val src = source
        val req = withContext(Dispatchers.Default) {
            var image: ByteArray? = null
            var iw = 0
            var ih = 0
            if (src != null) {
                val fitted = DesktopMedia.resize(src, width, height)
                image = DesktopMedia.toRgb(fitted)
                iw = fitted.width
                ih = fitted.height
            }
            SdVideoRequest(
                prompt = prompt.trim(), negative = negative.trim(), width = width / 16 * 16, height = height / 16 * 16,
                frames = frames, fps = fps, steps = steps, cfg = cfg, seed = seed, sampler = sampler,
                flowShift = flowShift, highNoiseSteps = highNoiseSteps, highNoiseCfg = cfg,
                vaeTiling = app.settings.sdVaeTiling, image = image, imageWidth = iw, imageHeight = ih,
            )
        }
        val v = engine.generateVideo(req, progress)
        if (v == null) {
            app.toast("Генерация видео остановлена или не удалась")
            return
        }
        phase = "Сохранение MP4"
        val file = File(Paths.outputs, "videos/${stamp()}_$seed.mp4")
        DesktopMedia.encodeMp4(v.frames, v.width, v.height, v.fps, file)
        val bitmaps = withContext(Dispatchers.Default) {
            v.frames.map { DesktopMedia.fromRgb(it, v.width, v.height).toComposeImageBitmap() }
        }
        videoResult = VideoResult(bitmaps, v.fps, file, (System.currentTimeMillis() - started) / 1000f)
        app.toast("Видео сохранено: ${file.name}")
    }

    fun cancel() {
        if (running) engine.cancel()
    }

    val isLoaded: Boolean get() = engine.isLoaded

    suspend fun unload() {
        cancel()
        job?.join()
        engine.unload()
        state = SdLoadState.None
    }

    fun shutdown() {
        engine.cancel()
        runBlocking { engine.unload() }
    }
}
