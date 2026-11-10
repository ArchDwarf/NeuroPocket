package com.neuropocket.app.ui

import android.app.Application
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
import com.neuropocket.app.data.HuggingFace
import com.neuropocket.app.data.Media
import com.neuropocket.app.data.Settings
import com.neuropocket.sd.SdEngine
import com.neuropocket.sd.SdLoadOptions
import com.neuropocket.sd.SdModelFiles
import com.neuropocket.sd.SdProgress
import com.neuropocket.sd.SdVideoRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jcodec.api.SequenceEncoder
import org.jcodec.common.Codec
import org.jcodec.common.Format
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Picture
import org.jcodec.common.model.Rational
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** Один файл видеомодели: роль в stable-diffusion.cpp и где его искать (по порядку). */
class VideoComponent(val role: String, val title: String, val sources: List<Pair<String, String>>)

/** Готовый набор для видео. */
class VideoPreset(
    val id: String,
    val title: String,
    val note: String,
    val canAnimatePhoto: Boolean,
    val components: List<VideoComponent>,
    val cfg: Float,
    val fps: Int,
    val flowShift: Float = 0f,
)

class VideoClip(val frames: List<Bitmap>, val fps: Int, val file: File, val prompt: String, val seconds: Float)

/** Размеры кадра для телефона (кратны 16). */
enum class VideoSize(val title: String, val w: Int, val h: Int) {
    SMALL("Быстро · 320×192", 320, 192),
    MEDIUM("Обычно · 480×272", 480, 272),
    LARGE("Лучше · 640×368", 640, 368),
    VERTICAL("Вертикально · 272×480", 272, 480),
}

/**
 * Генерация видео по описанию (Wan 2.1 / 2.2 через stable-diffusion.cpp) прямо на телефоне.
 * Файлы моделей скачиваются системным загрузчиком в video_models/<набор>/.
 */
class VideoStudio(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val toast: (String) -> Unit,
    /** Освободить память перед загрузкой (чат-модель, модель картинок). */
    private val beforeLoad: suspend () -> Unit,
) {
    private val engine = SdEngine()
    private val dm = app.getSystemService(DownloadManager::class.java)
    private val prefs = app.getSharedPreferences("video_models", Context.MODE_PRIVATE)

    val presets = listOf(
        VideoPreset(
            "wan21-1.3b", "Wan 2.1 · 1.3B", "Текст → видео. Самая лёгкая: ≈6 ГБ на диске, телефон с 8 ГБ ОЗУ и больше.",
            false,
            listOf(
                VideoComponent(
                    "diffusion_model", "Видеомодель",
                    listOf(
                        "samuelchristlie/Wan2.1-T2V-1.3B-GGUF" to "(?i)Q8_0\\.gguf$",
                        "QuantStack/Wan2.1-T2V-1.3B-GGUF" to "(?i)Q8_0\\.gguf$",
                        "Comfy-Org/Wan_2.1_ComfyUI_repackaged" to "split_files/diffusion_models/wan2\\.1_t2v_1\\.3B_fp16\\.safetensors$",
                    ),
                ),
                VideoComponent(
                    "vae", "VAE",
                    listOf("Comfy-Org/Wan_2.1_ComfyUI_repackaged" to "split_files/vae/wan_2\\.1_vae\\.safetensors$"),
                ),
                T5,
            ),
            cfg = 6f, fps = 16,
        ),
        VideoPreset(
            "wan22-5b", "Wan 2.2 · 5B", "Текст или фото → видео, лучше качество. ≈7 ГБ на диске, нужен телефон с 12 ГБ ОЗУ и больше.",
            true,
            listOf(
                VideoComponent(
                    "diffusion_model", "Видеомодель",
                    listOf(
                        "QuantStack/Wan2.2-TI2V-5B-GGUF" to "(?i)Q4_K_M\\.gguf$",
                        "QuantStack/Wan2.2-TI2V-5B-GGUF" to "(?i)Q4.*\\.gguf$",
                    ),
                ),
                VideoComponent(
                    "vae", "VAE",
                    listOf("Comfy-Org/Wan_2.2_ComfyUI_Repackaged" to "split_files/vae/wan2\\.2_vae\\.safetensors$"),
                ),
                T5,
            ),
            cfg = 5f, fps = 24,
        ),
    )

    // ----- Состояние -----
    var presetId by mutableStateOf(prefs.getString("preset", presets.first().id)!!)
        private set
    val preset: VideoPreset get() = presets.find { it.id == presetId } ?: presets.first()

    /** Прогресс загрузок по наборам: id → доля 0..1 (нет ключа — не качается). */
    val downloadProgress = mutableStateMapOf<String, Float>()
    var resolving by mutableStateOf<String?>(null); private set

    /** Меняется, когда набор скачан или удалён (для перерисовки). */
    var revision by mutableStateOf(0); private set

    var loadedId by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set

    var prompt by mutableStateOf("")
    var negative by mutableStateOf(
        "static, blurry, low quality, worst quality, jpeg artifacts, deformed, ugly, extra fingers, text, watermark, still image",
    )
    var size by mutableStateOf(VideoSize.MEDIUM)
    var frames by mutableStateOf(17)
    var steps by mutableStateOf(20)
    var source by mutableStateOf<Bitmap?>(null)

    var generating by mutableStateOf(false); private set
    var phase by mutableStateOf(""); private set
    var step by mutableStateOf(0); private set
    var totalSteps by mutableStateOf(0); private set
    var secondsPerStep by mutableStateOf(0f); private set
    var clip by mutableStateOf<VideoClip?>(null)
    private var job: Job? = null

    val isLoaded: Boolean get() = engine.isLoaded

    init {
        scope.launch { pollDownloads() }
    }

    fun choose(p: VideoPreset) {
        presetId = p.id
        prefs.edit { putString("preset", p.id) }
        if (!p.canAnimatePhoto) source = null
    }

    // ===================== Файлы =====================

    private fun dirOf(p: VideoPreset): File =
        File(app.getExternalFilesDir("video_models") ?: error("Нет доступа к памяти телефона"), p.id).also { it.mkdirs() }

    /** Скачанные файлы набора: роль → путь (только завершённые). */
    private fun files(p: VideoPreset): Map<String, File> {
        val json = JSONObject(prefs.getString("files_${p.id}", "{}") ?: "{}")
        return p.components.mapNotNull { c ->
            val name = json.optString(c.role).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val f = File(dirOf(p), name)
            if (f.exists()) c.role to f else null
        }.toMap()
    }

    fun isInstalled(p: VideoPreset): Boolean = files(p).size == p.components.size

    fun installedSizeGb(p: VideoPreset): Float = files(p).values.sumOf { it.length() } / 1e9f

    fun download(p: VideoPreset) {
        if (resolving != null || p.id in downloadProgress) return
        resolving = p.id
        scope.launch {
            try {
                val token = settings().hfToken
                val names = JSONObject(prefs.getString("files_${p.id}", "{}") ?: "{}")
                val ids = JSONObject(prefs.getString("dl_${p.id}", "{}") ?: "{}")
                val trees = HashMap<String, List<HuggingFace.RemoteFile>>()
                for (c in p.components) {
                    val existing = names.optString(c.role)
                    if (existing.isNotEmpty() && File(dirOf(p), existing).exists()) continue
                    if (ids.has(c.role)) continue
                    var found: Pair<String, HuggingFace.RemoteFile>? = null
                    for ((repo, pattern) in c.sources) {
                        val tree = trees.getOrPut(repo) { runCatching { HuggingFace.tree(repo, token) }.getOrDefault(emptyList()) }
                        val rx = Regex(pattern)
                        tree.firstOrNull { rx.containsMatchIn(it.path) }?.let { found = repo to it }
                        if (found != null) break
                    }
                    val (repo, file) = found ?: throw IllegalStateException(
                        "Не найден файл «${c.title}» на Hugging Face. Проверьте интернет или токен в настройках.",
                    )
                    val name = "${c.role}__${file.name}"
                    File(dirOf(p), "$name.part").delete()
                    val req = DownloadManager.Request(HuggingFace.downloadUrl(repo, file.path).toUri())
                        .setTitle(file.name)
                        .setDescription("Видеомодель для NeuroPocket")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true)
                        .setDestinationInExternalFilesDir(app, "video_models/${p.id}", "$name.part")
                    if (token.isNotBlank()) req.addRequestHeader("Authorization", "Bearer ${token.trim()}")
                    ids.put(c.role, JSONObject().put("id", dm.enqueue(req)).put("name", name))
                }
                prefs.edit { putString("dl_${p.id}", ids.toString()) }
                if (ids.length() > 0) downloadProgress[p.id] = 0f
                else if (isInstalled(p)) toast("${p.title} уже скачана")
            } catch (e: Throwable) {
                toast(e.message ?: "Ошибка загрузки")
            } finally {
                resolving = null
            }
        }
    }

    fun cancelDownload(p: VideoPreset) {
        val ids = JSONObject(prefs.getString("dl_${p.id}", "{}") ?: "{}")
        for (k in ids.keys()) {
            val o = ids.getJSONObject(k)
            dm.remove(o.getLong("id"))
            File(dirOf(p), o.getString("name") + ".part").delete()
        }
        prefs.edit { remove("dl_${p.id}") }
        downloadProgress.remove(p.id)
    }

    fun delete(p: VideoPreset) {
        scope.launch {
            if (loadedId == p.id) unload()
            cancelDownload(p)
            dirOf(p).deleteRecursively()
            prefs.edit { remove("files_${p.id}") }
            revision++
            toast("${p.title} удалена")
        }
    }

    private suspend fun pollDownloads() {
        while (scope.isActive) {
            for (p in presets) {
                val raw = prefs.getString("dl_${p.id}", null) ?: continue
                val ids = JSONObject(raw)
                if (ids.length() == 0) continue
                var done = 0L
                var total = 0L
                val names = JSONObject(prefs.getString("files_${p.id}", "{}") ?: "{}")
                val finished = mutableListOf<String>()
                for (role in ids.keys().asSequence().toList()) {
                    val o = ids.getJSONObject(role)
                    val id = o.getLong("id")
                    val name = o.getString("name")
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) {
                            finished += role
                            return@use
                        }
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val size = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        done += got
                        total += maxOf(size, got)
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                File(dirOf(p), "$name.part").renameTo(File(dirOf(p), name))
                                names.put(role, name)
                                finished += role
                            }
                            DownloadManager.STATUS_FAILED -> {
                                File(dirOf(p), "$name.part").delete()
                                dm.remove(id)
                                finished += role
                                toast("Не удалось скачать $name — попробуйте ещё раз")
                            }
                        }
                    }
                }
                finished.forEach { ids.remove(it) }
                prefs.edit {
                    putString("files_${p.id}", names.toString())
                    putString("dl_${p.id}", ids.toString())
                }
                if (ids.length() == 0) {
                    downloadProgress.remove(p.id)
                    revision++
                    if (isInstalled(p)) toast("${p.title} скачана — можно создавать видео")
                } else {
                    downloadProgress[p.id] = if (total > 0) done.toFloat() / total else 0f
                }
            }
            // Подхватываем загрузки, начатые до перезапуска приложения
            for (p in presets) {
                if (p.id !in downloadProgress && (JSONObject(prefs.getString("dl_${p.id}", "{}") ?: "{}").length() > 0)) {
                    downloadProgress[p.id] = 0f
                }
            }
            delay(1000)
        }
    }

    // ===================== Модель =====================

    private suspend fun ensureLoaded(p: VideoPreset): String? {
        if (loadedId == p.id && engine.isLoaded) return null
        val f = files(p)
        if (f.size != p.components.size) return "Сначала скачайте ${p.title}"
        loading = true
        phase = "Загрузка модели в память…"
        try {
            engine.unload()
            loadedId = null
            beforeLoad()
            var files = SdModelFiles()
            for ((role, file) in f) {
                files = when (role) {
                    "diffusion_model" -> files.copy(diffusionModel = file.absolutePath)
                    "vae" -> files.copy(vae = file.absolutePath)
                    "t5xxl" -> files.copy(t5xxl = file.absolutePath)
                    "clip_vision" -> files.copy(clipVision = file.absolutePath)
                    else -> files
                }
            }
            engine.load(files, SdLoadOptions(threads = settings().threads))
            loadedId = p.id
            return null
        } catch (e: Throwable) {
            return e.message ?: e.toString()
        } finally {
            loading = false
        }
    }

    suspend fun unload() {
        if (generating) {
            engine.cancel()
            job?.join()
        }
        engine.unload()
        loadedId = null
    }

    // ===================== Генерация =====================

    fun generate() {
        if (generating || loading) return
        val p = preset
        if (prompt.isBlank()) {
            toast("Опишите, что должно происходить в видео")
            return
        }
        if (!isInstalled(p)) {
            toast("Сначала скачайте модель ${p.title}")
            return
        }
        generating = true
        step = 0
        totalSteps = steps
        secondsPerStep = 0f
        job = scope.launch {
            val started = System.currentTimeMillis()
            try {
                ensureLoaded(p)?.let { err ->
                    toast("Не удалось загрузить модель: $err")
                    return@launch
                }
                phase = "Создание кадров"
                val src = if (p.canAnimatePhoto) source else null
                val req = withContext(Dispatchers.Default) {
                    var w = size.w
                    var h = size.h
                    var image: ByteArray? = null
                    if (src != null) {
                        // Пропорции фото, площадь — как у выбранного размера
                        val k = kotlin.math.sqrt(w.toDouble() * h / (src.width.toDouble() * src.height))
                        w = maxOf(16, (src.width * k).toInt() / 16 * 16)
                        h = maxOf(16, (src.height * k).toInt() / 16 * 16)
                        image = Media.toRgb(Bitmap.createScaledBitmap(src, w, h, true))
                    }
                    SdVideoRequest(
                        prompt = prompt.trim(), negative = negative.trim(), width = w, height = h,
                        frames = frames, fps = p.fps, steps = steps, cfg = p.cfg,
                        seed = Random.nextLong(0, Int.MAX_VALUE.toLong()), sampler = "euler",
                        flowShift = p.flowShift, vaeTiling = true,
                        image = image, imageWidth = if (image != null) w else 0, imageHeight = if (image != null) h else 0,
                    )
                }
                val v = engine.generateVideo(req, SdProgress { st, total, sec ->
                    step = st
                    totalSteps = total
                    secondsPerStep = sec
                })
                if (v == null) {
                    toast("Создание видео остановлено")
                    return@launch
                }
                phase = "Сохранение MP4"
                val dir = File(app.getExternalFilesDir("videos") ?: File(app.filesDir, "videos"), "").also { it.mkdirs() }
                val file = File(dir, "NeuroPocket_${System.currentTimeMillis()}.mp4")
                val bitmaps = withContext(Dispatchers.Default) {
                    encodeMp4(v.frames, v.width, v.height, v.fps, file)
                    v.frames.map { Media.fromRgb(it, v.width, v.height) }
                }
                clip = VideoClip(bitmaps, v.fps, file, req.prompt, (System.currentTimeMillis() - started) / 1000f)
            } catch (e: Throwable) {
                toast("Ошибка: ${e.message}")
            } finally {
                generating = false
                phase = ""
            }
        }
    }

    fun cancel() {
        if (generating) engine.cancel()
    }

    /** Копирует MP4 в галерею (Movies/NeuroPocket). */
    fun saveToGallery(c: VideoClip) {
        scope.launch {
            val ok = runCatching { copyToGallery(c.file) }.isSuccess
            toast(if (ok) "Сохранено в галерею: Movies/NeuroPocket" else "Не удалось сохранить видео")
        }
    }

    private suspend fun copyToGallery(f: File) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NeuroPocket")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val r = app.contentResolver
            val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("нет доступа")
            r.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            r.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "NeuroPocket")
            dir.mkdirs()
            f.copyTo(File(dir, f.name), overwrite = true)
        }
    }

    companion object {
        private val T5 = VideoComponent(
            "t5xxl", "Текстовый энкодер UMT5",
            listOf(
                "city96/umt5-xxl-encoder-gguf" to "(?i)Q4_K_M\\.gguf$",
                "city96/umt5-xxl-encoder-gguf" to "(?i)Q5_K_M\\.gguf$",
                "city96/umt5-xxl-encoder-gguf" to "(?i)Q8_0\\.gguf$",
            ),
        )

        /** RGB-кадры → MP4 (H.264) без зависимостей от системы. */
        fun encodeMp4(frames: List<ByteArray>, w: Int, h: Int, fps: Int, out: File) {
            val ew = w and 1.inv()
            val eh = h and 1.inv()
            val ch = NIOUtils.writableFileChannel(out.absolutePath)
            try {
                val enc = SequenceEncoder(ch, Rational.R(fps, 1), Format.MOV, Codec.H264, null)
                for (f in frames) {
                    val pic = Picture.create(ew, eh, ColorSpace.RGB)
                    val dst = pic.getPlaneData(0)
                    var o = 0
                    for (y in 0 until eh) {
                        var i = y * w * 3
                        for (x in 0 until ew * 3) {
                            dst[o++] = ((f[i++].toInt() and 0xFF) - 128).toByte()
                        }
                    }
                    enc.encodeNativeFrame(pic)
                }
                enc.finish()
            } finally {
                NIOUtils.closeQuietly(ch)
            }
        }
    }
}
