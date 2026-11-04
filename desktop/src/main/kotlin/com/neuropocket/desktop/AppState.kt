package com.neuropocket.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neuropocket.app.data.HfModelFile
import com.neuropocket.app.data.HfRepo
import com.neuropocket.app.data.HuggingFace
import com.neuropocket.app.data.ModelKind
import com.neuropocket.sd.SdLibrary
import com.neuropocket.speech.HttpDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.management.ManagementFactory

/** Загрузка одного файла. */
class DownloadTask(val title: String, val url: String, val target: File) {
    var done by mutableStateOf(0L)
    var total by mutableStateOf(-1L)
    var stage by mutableStateOf("В очереди")
    var job: Job? = null
    val progress: Float get() = if (total > 0) done.toFloat() / total else -1f
}

enum class Tab(val title: String, val icon: String) {
    CHAT("Чат", "💬"), IMAGES("Картинки", "🎨"), VIDEO("Видео", "🎬"), SPEECH("Речь", "🎙"),
    MODELS("Модели", "📦"), SETTINGS("Настройки", "⚙"),
}

/** Всё состояние компьютерного приложения. */
class AppState(val scope: CoroutineScope) {
    val snackbar = SnackbarHostState()
    var tab by mutableStateOf(Tab.CHAT)

    var settings by mutableStateOf(DesktopSettings.load()); private set

    val totalRamBytes: Long = try {
        (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize
    } catch (_: Throwable) {
        0L
    }

    init {
        // Выбор версии stable-diffusion.cpp (Vulkan или CPU) — до первой загрузки модели
        SdLibrary.preferGpu = settings.useGpu
    }

    val chat = ChatController(this)
    val images = ImageController(this, video = false)
    val video = ImageController(this, video = true)
    val speech = SpeechController(this)

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg, withDismissAction = true) }
    }

    fun updateSettings(new: DesktopSettings) {
        val old = settings
        settings = new
        new.save()
        if (old.useGpu != new.useGpu) {
            toast("Переключение видеокарты для картинок и видео применится после перезапуска приложения")
        }
        if (old.contextSize != new.contextSize || old.threads != new.threads ||
            old.gpuLayers != new.gpuLayers || old.useGpu != new.useGpu
        ) chat.reloadIfLoaded()
    }

    // ===================== Модели на диске =====================

    var chatModels by mutableStateOf<List<File>>(emptyList()); private set
    var imageFiles by mutableStateOf<List<File>>(emptyList()); private set
    var profiles by mutableStateOf<List<SdProfile>>(emptyList()); private set

    fun refreshModels() {
        chatModels = Paths.chatModels.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".gguf", true) && !it.name.contains("mmproj", true) }
            .filter { f -> Regex("-(\\d{5})-of-\\d{5}\\.gguf$").find(f.name)?.groupValues?.get(1)?.let { it == "00001" } ?: true }
            .sortedBy { it.name.lowercase() }.toList()
        imageFiles = Paths.imageModels.walkTopDown()
            .filter { it.isFile && listOf(".safetensors", ".gguf", ".ckpt", ".sft", ".pt", ".pth", ".bin").any { e -> it.name.endsWith(e, true) } }
            .sortedBy { it.name.lowercase() }.toList()
        profiles = SdProfile.loadAll()
        speech.refresh()
    }

    fun saveProfile(p: SdProfile) {
        profiles = profiles.filter { it.name != p.name } + p
        SdProfile.saveAll(profiles)
    }

    fun deleteProfile(p: SdProfile) {
        profiles = profiles.filter { it.name != p.name }
        SdProfile.saveAll(profiles)
    }

    /** Модуль зрения: «<модель>.mmproj» или (для файлов вне папки моделей) mmproj-*.gguf рядом. */
    fun mmprojFor(model: File): File? {
        File(model.path + ".mmproj").takeIf { it.exists() }?.let { return it }
        if (model.parentFile?.canonicalPath == Paths.chatModels.canonicalPath) return null
        return model.parentFile?.listFiles { f -> f.isFile && f.name.contains("mmproj", true) && f.name.endsWith(".gguf", true) }
            ?.minByOrNull { it.length() }
    }

    // ===================== Загрузки =====================

    val downloads = mutableStateListOf<DownloadTask>()

    private val hfHeaders: Map<String, String>
        get() = if (settings.hfToken.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${settings.hfToken.trim()}")

    /** Ставит файл в загрузку; [onDone] вызывается после успешного завершения. */
    fun download(title: String, url: String, target: File, onDone: suspend (File) -> Unit = {}): DownloadTask? {
        if (target.exists()) {
            scope.launch { onDone(target) }
            return null
        }
        downloads.find { it.target == target }?.let { return it }
        val task = DownloadTask(title, url, target)
        downloads += task
        task.job = scope.launch {
            try {
                task.stage = "Загрузка"
                val headers = if ("huggingface.co" in url) hfHeaders else emptyMap()
                HttpDownloader.download(url, target, headers) { d, t ->
                    task.done = d
                    task.total = t
                }
                onDone(target)
                toast("Скачано: ${target.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Ошибка загрузки ${target.name}: ${e.message}")
            } finally {
                downloads.remove(task)
                refreshModels()
            }
        }
        return task
    }

    fun cancelDownload(task: DownloadTask) {
        task.job?.cancel()
        downloads.remove(task)
    }

    /** Чат-модель с Hugging Face (с модулем зрения, если есть). */
    fun downloadChatModel(f: HfModelFile) {
        val dir = Paths.chatModels
        for (part in f.parts) {
            download(part.substringAfterLast('/'), HuggingFace.downloadUrl(f.repo, part), File(dir, part.substringAfterLast('/')))
        }
        f.mmproj?.let { mp ->
            val first = f.parts.first().substringAfterLast('/')
            download("Модуль зрения для $first", HuggingFace.downloadUrl(f.repo, mp), File(dir, "$first.mmproj"))
        }
    }

    /** Любой файл любого репозитория — в папку картинок/видео (или чата для .gguf из «Чат»). */
    fun downloadRepoFile(repo: String, path: String, toChat: Boolean) {
        val base = if (toChat) Paths.chatModels else File(Paths.imageModels, repo.replace('/', '_'))
        download(path.substringAfterLast('/'), HuggingFace.downloadUrl(repo, path), File(base, path.substringAfterLast('/')))
    }

    /** Скачивает готовый набор и создаёт профиль. */
    fun installPreset(preset: Preset) {
        scope.launch {
            try {
                toast("Подготовка: ${preset.title}…")
                val trees = mutableMapOf<String, List<HuggingFace.RemoteFile>>()
                var files = preset.profile.files
                val pending = mutableListOf<Triple<String, String, File>>() // url, title, target
                for (pf in preset.files) {
                    val tree = trees.getOrPut(pf.repo) { HuggingFace.tree(pf.repo, settings.hfToken) }
                    val found = pf.patterns.firstNotNullOfOrNull { pat ->
                        val re = Regex(pat)
                        tree.filter { re.containsMatchIn(it.path) }.minByOrNull { it.size }
                    } ?: throw IllegalStateException("В ${pf.repo} не найден файл для «${pf.role}»")
                    val target = File(File(Paths.imageModels, pf.repo.replace('/', '_')), found.name)
                    files = files.with(pf.role, target.absolutePath)
                    pending += Triple(HuggingFace.downloadUrl(pf.repo, found.path), found.name, target)
                }
                val profile = preset.profile.copy(files = files, note = preset.note)
                saveProfile(profile)
                if (preset.video) {
                    if (settings.videoProfile.isBlank()) updateSettings(settings.copy(videoProfile = profile.name))
                } else if (settings.imageProfile.isBlank()) updateSettings(settings.copy(imageProfile = profile.name))
                var left = pending.count { !it.third.exists() }
                if (left == 0) toast("${preset.title}: все файлы уже скачаны")
                for ((url, title, target) in pending) {
                    download(title, url, target) {
                        left--
                        if (left <= 0) toast("Набор «${preset.title}» готов")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Не удалось подготовить набор: ${e.message}")
            }
        }
    }

    // ===================== Каталог Hugging Face =====================

    var hfKind by mutableStateOf(ModelKind.CHAT)
    var hfQuery by mutableStateOf("")
    var hfResults by mutableStateOf<List<HfRepo>>(emptyList()); private set
    var hfLoading by mutableStateOf(false); private set
    var openRepo by mutableStateOf<String?>(null); private set
    var repoChatFiles by mutableStateOf<List<HfModelFile>>(emptyList()); private set
    var repoAllFiles by mutableStateOf<List<HuggingFace.RemoteFile>>(emptyList()); private set
    var repoLoading by mutableStateOf(false); private set

    fun searchHf() {
        scope.launch {
            hfLoading = true
            try {
                hfResults = if (hfKind == ModelKind.CHAT) HuggingFace.search(hfQuery, ModelKind.CHAT, settings.hfToken)
                else HuggingFace.searchAny(hfQuery, settings.hfToken)
                if (hfResults.isEmpty()) toast("Ничего не найдено")
            } catch (e: Exception) {
                toast("Ошибка поиска: ${e.message}")
            } finally {
                hfLoading = false
            }
        }
    }

    fun openRepository(id: String) {
        val repo = id.trim().removePrefix("https://huggingface.co/").substringBefore("/tree/").substringBefore("/blob/").trim('/')
        if (repo.count { it == '/' } != 1) {
            toast("Укажите репозиторий в виде автор/название")
            return
        }
        openRepo = repo
        repoChatFiles = emptyList()
        repoAllFiles = emptyList()
        repoLoading = true
        scope.launch {
            try {
                repoAllFiles = HuggingFace.tree(repo, settings.hfToken).sortedBy { it.path.lowercase() }
                repoChatFiles = runCatching { HuggingFace.listFiles(repo, ModelKind.CHAT, settings.hfToken) }.getOrDefault(emptyList())
            } catch (e: Exception) {
                toast(e.message ?: "Ошибка")
            } finally {
                repoLoading = false
            }
        }
    }

    fun closeRepository() {
        openRepo = null
    }

    // ===================== Удаление =====================

    fun deleteFile(f: File) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (f.isDirectory) f.deleteRecursively() else {
                    f.delete()
                    File(f.path + ".mmproj").delete()
                }
            }
            refreshModels()
        }
    }

    fun shutdown() {
        chat.shutdown()
        images.shutdown()
        video.shutdown()
        speech.shutdown()
    }

    init {
        refreshModels()
        chat.autoload()
    }
}
