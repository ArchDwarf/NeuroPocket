package com.neuropocket.app.ui

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neuropocket.app.data.AppSettings
import com.neuropocket.app.data.Attachment
import com.neuropocket.app.data.AttachmentKind
import com.neuropocket.app.data.DownloadInfo
import com.neuropocket.app.data.HfModelFile
import com.neuropocket.app.data.HfRepo
import com.neuropocket.app.data.HuggingFace
import com.neuropocket.app.data.LocalModel
import com.neuropocket.app.data.Media
import com.neuropocket.app.data.ModelKind
import com.neuropocket.app.data.ModelRepository
import com.neuropocket.app.data.Settings
import com.neuropocket.app.engine.ChatImage
import com.neuropocket.app.engine.ChatTurn
import com.neuropocket.app.engine.GenEvent
import com.neuropocket.app.engine.LlamaEngine
import com.neuropocket.app.engine.LlamaNative
import com.neuropocket.app.engine.SamplingParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val stats: String? = null,
    val isError: Boolean = false,
)

sealed interface ModelState {
    data object None : ModelState
    data class Loading(val name: String) : ModelState
    data class Ready(val name: String, val info: String, val hasVision: Boolean = false) : ModelState
    data class Error(val name: String, val message: String) : ModelState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ModelRepository(app)
    private val settingsStore = AppSettings(app)
    private val engine = LlamaEngine(app.applicationInfo.nativeLibraryDir)
    private val ids = AtomicLong(0)

    val totalRamBytes: Long = app.getSystemService(ActivityManager::class.java)
        .let { am -> ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem }

    /** На телефонах с малым объёмом памяти чат-модель и модель картинок не держим одновременно. */
    private val lowMemory: Boolean get() = totalRamBytes < 11L * 1024 * 1024 * 1024

    private val _toasts = Channel<String>(Channel.BUFFERED)
    val toasts = _toasts.receiveAsFlow()

    var settings by mutableStateOf(settingsStore.load()); private set

    // ----- Модели -----
    var installed by mutableStateOf<List<LocalModel>>(emptyList()); private set
    var installedImage by mutableStateOf<List<LocalModel>>(emptyList()); private set
    var downloads by mutableStateOf<List<DownloadInfo>>(emptyList()); private set
    var modelState by mutableStateOf<ModelState>(ModelState.None); private set
    var importProgress by mutableStateOf<Float?>(null); private set

    // ----- Каталог Hugging Face -----
    var browseKind by mutableStateOf(ModelKind.CHAT); private set

    fun switchKind(kind: ModelKind) {
        if (kind == browseKind) return
        browseKind = kind
        searchResults = emptyList()
        searchError = null
    }
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<HfRepo>>(emptyList()); private set
    var searchLoading by mutableStateOf(false); private set
    var searchError by mutableStateOf<String?>(null); private set
    var openRepo by mutableStateOf<String?>(null); private set
    var openRepoKind by mutableStateOf(ModelKind.CHAT); private set
    var repoFiles by mutableStateOf<List<HfModelFile>>(emptyList()); private set
    var repoLoading by mutableStateOf(false); private set
    var repoError by mutableStateOf<String?>(null); private set

    // ----- Чат -----
    val messages = mutableStateListOf<ChatMessage>()
    val pendingAttachments = mutableStateListOf<Attachment>()
    var attachLoading by mutableStateOf(false); private set
    var isGenerating by mutableStateOf(false); private set
    private var genJob: Job? = null
    /** С какого сообщения начинается контекст (старые отбрасываются, если не влезают). */
    private var contextStart = 0

    // ----- Картинки -----
    val studio = ImageStudio(
        app = app,
        scope = viewModelScope,
        settings = { settings },
        saveSettings = { updateSettings(it, reload = false) },
        toast = ::toast,
        beforeLoad = ::freeChatIfLowMemory,
    )

    init {
        refreshInstalled()
        viewModelScope.launch { pollDownloads() }
        val last = settings.activeModelPath
        if (last.isNotBlank() && File(last).exists()) loadModel(last)
    }

    private fun toast(msg: String) {
        _toasts.trySend(msg)
    }

    // ===================== Модели =====================

    fun refreshInstalled() {
        installed = repo.installedModels()
        installedImage = repo.installedImageModels()
    }

    private suspend fun pollDownloads() {
        while (true) {
            val (active, errors) = withContext(Dispatchers.IO) { repo.syncDownloads() }
            val before = downloads.map { it.fileName }.toSet()
            downloads = active
            errors.forEach { toast(it) }
            val finished = before - active.map { it.fileName }.toSet()
            if (finished.isNotEmpty() || errors.isNotEmpty()) {
                val prev = installed.find { it.path == settings.activeModelPath }
                refreshInstalled()
                if (errors.isEmpty()) toast("Загрузка завершена: ${finished.joinToString()}")
                val now = installed.find { it.path == settings.activeModelPath }
                when {
                    modelState is ModelState.None && active.isEmpty() ->
                        installed.firstOrNull()?.let { selectModel(it) }
                    // Докачался модуль зрения для текущей модели — перезагружаем
                    prev != null && now != null && !prev.hasVision && now.hasVision -> loadModel(now.path)
                }
            }
            delay(if (active.isEmpty()) 3000L else 1000L)
        }
    }

    fun download(file: HfModelFile) {
        repo.enqueue(file, settings.hfToken)
        val extra = if (file.mmproj != null) " + модуль зрения" else ""
        toast("Загрузка начата: ${file.displayName}$extra")
        viewModelScope.launch {
            downloads = withContext(Dispatchers.IO) { repo.syncDownloads().first }
        }
    }

    fun cancelDownload(id: Long) {
        repo.cancel(id)
        downloads = downloads.filterNot { it.id == id }
    }

    fun importModel(uri: Uri, kind: ModelKind) {
        if (importProgress != null) return
        importProgress = 0f
        viewModelScope.launch {
            try {
                val model = repo.import(uri, kind) { p -> importProgress = p }
                refreshInstalled()
                toast("Модель добавлена: ${model.name}")
                if (kind == ModelKind.CHAT && modelState !is ModelState.Ready) selectModel(model)
            } catch (e: Exception) {
                toast(e.message ?: "Ошибка импорта")
            } finally {
                importProgress = null
            }
        }
    }

    fun importMmproj(model: LocalModel, uri: Uri) {
        if (importProgress != null) return
        importProgress = 0f
        viewModelScope.launch {
            try {
                repo.importMmproj(model, uri) { p -> importProgress = p }
                refreshInstalled()
                toast("Зрение подключено к ${model.name}")
                if (settings.activeModelPath == model.path) loadModel(model.path)
            } catch (e: Exception) {
                toast(e.message ?: "Ошибка импорта")
            } finally {
                importProgress = null
            }
        }
    }

    fun selectModel(model: LocalModel) {
        updateSettings(settings.copy(activeModelPath = model.path), reload = false)
        loadModel(model.path)
    }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            if (model.kind == ModelKind.IMAGE) {
                studio.onModelDeleted(model)
            } else if (settings.activeModelPath == model.path) {
                stopGeneration()
                genJob?.join()
                engine.unload()
                modelState = ModelState.None
                updateSettings(settings.copy(activeModelPath = ""), reload = false)
            }
            withContext(Dispatchers.IO) { repo.delete(model) }
            refreshInstalled()
        }
    }

    private fun loadModel(path: String) {
        val name = File(path).name
        val mmproj = File(path + ModelRepository.MMPROJ_SUFFIX).takeIf { it.exists() }?.absolutePath
        viewModelScope.launch {
            stopGeneration()
            genJob?.join()
            if (lowMemory && studio.isModelLoaded) {
                studio.unload()
                toast("Модель картинок выгружена, чтобы хватило памяти")
            }
            modelState = ModelState.Loading(name)
            modelState = try {
                val loaded = engine.load(path, mmproj, settings.contextSize, settings.threads)
                contextStart = 0
                if (mmproj != null && !loaded.hasVision) toast("Не удалось загрузить модуль зрения — модель работает только с текстом")
                ModelState.Ready(name, loaded.info, loaded.hasVision)
            } catch (e: Throwable) {
                ModelState.Error(name, e.message ?: e.toString())
            }
        }
    }

    /** Освобождает память под модель картинок на слабых телефонах. */
    private suspend fun freeChatIfLowMemory() {
        if (lowMemory && engine.isLoaded) {
            stopGeneration()
            genJob?.join()
            engine.unload()
            modelState = ModelState.None
            toast("Чат-модель выгружена, чтобы хватило памяти для картинок")
        }
    }

    fun reloadModel() {
        settings.activeModelPath.takeIf { it.isNotBlank() }?.let { loadModel(it) }
    }

    // ===================== Hugging Face =====================

    fun search() {
        viewModelScope.launch {
            searchLoading = true
            searchError = null
            try {
                searchResults = HuggingFace.search(searchQuery, browseKind, settings.hfToken)
                if (searchResults.isEmpty()) searchError = "Ничего не найдено"
            } catch (e: Exception) {
                searchError = "Не удалось выполнить поиск: ${e.message}"
            } finally {
                searchLoading = false
            }
        }
    }

    fun openRepository(id: String, kind: ModelKind = browseKind) {
        val repoId = id.trim().removePrefix("https://huggingface.co/").trim('/')
        if (repoId.count { it == '/' } != 1) {
            toast("Укажите репозиторий в виде автор/название")
            return
        }
        openRepo = repoId
        openRepoKind = kind
        repoFiles = emptyList()
        repoError = null
        repoLoading = true
        viewModelScope.launch {
            try {
                repoFiles = HuggingFace.listFiles(repoId, kind, settings.hfToken)
                if (repoFiles.isEmpty()) {
                    repoError = if (kind == ModelKind.CHAT) "В репозитории нет GGUF-файлов"
                    else "Нет подходящих однофайловых моделей (.safetensors / .ckpt / .gguf). " +
                        "Модели в формате diffusers (по папкам) не поддерживаются."
                }
            } catch (e: Exception) {
                repoError = e.message ?: "Ошибка загрузки списка файлов"
            } finally {
                repoLoading = false
            }
        }
    }

    fun closeRepository() {
        openRepo = null
    }

    // ===================== Настройки =====================

    val modelsDirPath: String get() = repo.modelsDir.absolutePath

    suspend fun systemInfo(): String = engine.systemInfo()

    fun updateSettings(new: Settings, reload: Boolean = true) {
        val needReload = reload &&
            (new.contextSize != settings.contextSize || new.threads != settings.threads)
        settings = new
        settingsStore.save(new)
        if (needReload && engine.isLoaded) reloadModel()
    }

    // ===================== Чат =====================

    val canAttach: Boolean get() = (modelState as? ModelState.Ready)?.hasVision == true

    fun attach(uris: List<Uri>) {
        if (!canAttach) {
            toast("Эта модель не видит изображения. Выберите модель со значком 👁")
            return
        }
        attachLoading = true
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    pendingAttachments += Media.loadAttachment(
                        getApplication(), uri, settings.visionMaxSide, settings.videoFrames,
                    )
                } catch (e: Exception) {
                    toast("Не удалось открыть файл: ${e.message}")
                }
            }
            attachLoading = false
        }
    }

    fun removeAttachment(a: Attachment) {
        pendingAttachments.remove(a)
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && pendingAttachments.isEmpty()) || isGenerating) return
        if (modelState !is ModelState.Ready) {
            toast("Сначала выберите и загрузите модель во вкладке «Модели»")
            return
        }
        val prompt = trimmed.ifEmpty {
            if (pendingAttachments.any { it.kind == AttachmentKind.VIDEO }) "Опиши, что происходит на видео."
            else "Опиши, что на изображении."
        }
        messages += ChatMessage(ids.incrementAndGet(), Role.USER, prompt, pendingAttachments.toList())
        pendingAttachments.clear()
        generate()
    }

    /** Перегенерировать последний ответ. */
    fun regenerate() {
        if (isGenerating || modelState !is ModelState.Ready) return
        if (messages.lastOrNull()?.role == Role.ASSISTANT) messages.removeAt(messages.lastIndex)
        if (messages.lastOrNull()?.role == Role.USER) generate()
    }

    /** Превращает сообщение пользователя с вложениями в реплику для модели. */
    private fun toTurn(m: ChatMessage, vision: Boolean): ChatTurn {
        if (m.role == Role.ASSISTANT) return ChatTurn("assistant", m.text)
        if (m.attachments.isEmpty() || !vision) return ChatTurn("user", m.text)
        val marker = engine.marker
        val images = mutableListOf<ChatImage>()
        val sb = StringBuilder()
        for (a in m.attachments) {
            when (a.kind) {
                AttachmentKind.IMAGE -> {
                    val f = a.frames.first()
                    images += ChatImage(a.id, f.rgb, f.width, f.height)
                    sb.append(marker).append('\n')
                }
                AttachmentKind.VIDEO -> {
                    sb.append("Видео длительностью ${a.durationSec.toInt()} с, ${a.frames.size} кадров:\n")
                    a.frames.forEachIndexed { i, f ->
                        images += ChatImage("${a.id}#$i", f.rgb, f.width, f.height)
                        sb.append('[').append(f.label).append("] ").append(marker).append('\n')
                    }
                }
            }
        }
        sb.append(m.text)
        return ChatTurn("user", sb.toString(), images)
    }

    private fun generate() {
        val replyId = ids.incrementAndGet()
        messages += ChatMessage(replyId, Role.ASSISTANT, "")
        isGenerating = true
        val vision = (modelState as? ModelState.Ready)?.hasVision == true

        genJob = viewModelScope.launch {
            val s = settings
            val params = SamplingParams(s.temperature, s.topP, s.topK, s.repeatPenalty, s.maxTokens)
            val sb = StringBuilder()
            var note: String? = null
            try {
                while (true) {
                    val history = messages
                        .drop(contextStart)
                        .filter { it.id != replyId && !it.isError }
                        .map { toTurn(it, vision) }
                    val turns = buildList {
                        if (s.systemPrompt.isNotBlank()) add(ChatTurn("system", s.systemPrompt))
                        addAll(history)
                    }

                    var done: GenEvent.Done? = null
                    engine.generate(turns, params).collect { ev ->
                        when (ev) {
                            is GenEvent.Text -> {
                                sb.append(ev.text)
                                update(replyId) { it.copy(text = sb.toString()) }
                            }
                            is GenEvent.Done -> done = ev
                        }
                    }
                    val d = done ?: break
                    when {
                        d.code == LlamaNative.RES_CONTEXT_FULL && sb.isEmpty() -> {
                            // Переписка не влезает в контекст — забываем самые старые сообщения
                            val lastUser = messages.indexOfLast { it.role == Role.USER }
                            if (contextStart < lastUser) {
                                contextStart = (contextStart + 2).coerceAtMost(lastUser)
                                note = "старые сообщения забыты: не хватает контекста"
                                continue
                            }
                            update(replyId) {
                                it.copy(text = "Сообщение слишком длинное для контекста модели. " +
                                    "Увеличьте «Размер контекста» в настройках" +
                                    (if (vision) ", уменьшите размер фото или число кадров видео." else "."),
                                    isError = true)
                            }
                        }
                        d.code == LlamaNative.RES_CONTEXT_FULL ->
                            note = "контекст заполнен — очистите чат"
                        d.code == LlamaNative.RES_MEDIA_ERROR -> update(replyId) {
                            it.copy(text = "Не удалось обработать изображение.", isError = true)
                        }
                        d.code < 0 -> update(replyId) {
                            it.copy(text = "Ошибка генерации (код ${d.code})", isError = true)
                        }
                        else -> {
                            val sec = d.millis / 1000.0
                            val speed = if (sec > 0) d.code / sec else 0.0
                            val stats = "%d ток. · %.1f с · %.1f ток/с".format(d.code, sec, speed)
                            update(replyId) { it.copy(stats = listOfNotNull(stats, note).joinToString(" · ")) }
                            note = null
                        }
                    }
                    note?.let { n -> update(replyId) { it.copy(stats = n) } }
                    break
                }
                if (sb.isEmpty() && messages.find { it.id == replyId }?.isError == false) {
                    update(replyId) { it.copy(text = "…", stats = "остановлено") }
                }
            } finally {
                isGenerating = false
            }
        }
    }

    private inline fun update(id: Long, f: (ChatMessage) -> ChatMessage) {
        val i = messages.indexOfLast { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    fun stopGeneration() {
        if (isGenerating) engine.stop()
    }

    fun clearChat() {
        viewModelScope.launch {
            stopGeneration()
            genJob?.join()
            messages.clear()
            pendingAttachments.clear()
            contextStart = 0
            engine.resetChat()
        }
    }

    override fun onCleared() {
        engine.stop()
        studio.cancel()
        runBlocking {
            engine.unload()
            studio.unload()
        }
    }
}
