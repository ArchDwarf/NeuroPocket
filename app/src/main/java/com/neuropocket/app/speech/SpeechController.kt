package com.neuropocket.app.speech

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neuropocket.app.data.Settings
import com.neuropocket.speech.HttpDownloader
import com.neuropocket.speech.SpeechCatalogItem
import com.neuropocket.speech.SpeechKind
import com.neuropocket.speech.SpeechModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Состояние установки речевой модели. */
data class InstallState(val title: String, val stage: String, val progress: Float)

/**
 * Речь на телефоне: установка моделей, голосовой ввод и озвучка ответов.
 */
class SpeechController(
    app: Application,
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val saveSettings: (Settings) -> Unit,
    private val toast: (String) -> Unit,
) {
    private val engine = SpeechEngine { settings().threads }
    val speechDir: File = (app.getExternalFilesDir("speech") ?: File(app.filesDir, "speech")).also { it.mkdirs() }

    var installed by mutableStateOf<List<SpeechModel>>(emptyList()); private set
    val installing = mutableStateMapOf<String, InstallState>()
    private val installJobs = mutableMapOf<String, Job>()

    var isRecording by mutableStateOf(false); private set
    var isTranscribing by mutableStateOf(false); private set
    var level by mutableStateOf(0f); private set
    /** id сообщения, которое сейчас озвучивается (-1 — ничего). */
    var speakingId by mutableStateOf(-1L); private set
    private var speakJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            installed = withContext(Dispatchers.IO) { SpeechModel.scan(speechDir) }
        }
    }

    val asrModel: SpeechModel? get() = installed.find { it.dir.path == settings().asrModelDir && it.kind == SpeechKind.ASR }
    val ttsModel: SpeechModel? get() = installed.find { it.dir.path == settings().ttsModelDir && it.kind == SpeechKind.TTS }

    fun isInstalled(item: SpeechCatalogItem) = installed.any { it.dir.name == item.dirName }

    // ===================== Установка =====================

    fun install(item: SpeechCatalogItem) = install(item.url, item.title)

    fun install(url: String, title: String = url.substringAfterLast('/')) {
        if (!url.startsWith("https://") || !url.endsWith(".tar.bz2")) {
            toast("Нужна ссылка на архив модели sherpa-onnx (.tar.bz2)")
            return
        }
        if (installing.containsKey(url)) return
        installing[url] = InstallState(title, "Загрузка", 0f)
        installJobs[url] = scope.launch {
            try {
                val model = HttpDownloader.installSpeechModel(url, speechDir) { stage, p ->
                    installing[url] = InstallState(title, stage, p)
                }
                installed = withContext(Dispatchers.IO) { SpeechModel.scan(speechDir) }
                // Первая установленная модель своего вида становится активной
                val s = settings()
                if (model.kind == SpeechKind.ASR && asrModel == null) saveSettings(s.copy(asrModelDir = model.dir.path))
                if (model.kind == SpeechKind.TTS && ttsModel == null) saveSettings(settings().copy(ttsModelDir = model.dir.path))
                toast("Установлено: ${model.title}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Ошибка установки: ${e.message}")
            } finally {
                installing.remove(url)
                installJobs.remove(url)
            }
        }
    }

    fun cancelInstall(url: String) {
        installJobs[url]?.cancel()
    }

    fun delete(model: SpeechModel) {
        scope.launch {
            withContext(Dispatchers.IO) { model.dir.deleteRecursively() }
            val s = settings()
            if (s.asrModelDir == model.dir.path) saveSettings(s.copy(asrModelDir = ""))
            if (settings().ttsModelDir == model.dir.path) saveSettings(settings().copy(ttsModelDir = ""))
            refresh()
        }
    }

    fun select(model: SpeechModel) {
        val s = settings()
        saveSettings(if (model.kind == SpeechKind.ASR) s.copy(asrModelDir = model.dir.path) else s.copy(ttsModelDir = model.dir.path))
    }

    fun saveSpeed(speed: Float) = saveSettings(settings().copy(ttsSpeed = Math.round(speed * 10) / 10f))

    fun saveAutoSpeak(on: Boolean) = saveSettings(settings().copy(autoSpeak = on))

    // ===================== Голосовой ввод =====================

    /** Начинает запись; по [stopDictation] распознаёт и передаёт текст в [onText]. */
    fun startDictation(onText: (String) -> Unit) {
        val model = asrModel ?: run {
            toast("Установите модель распознавания речи во вкладке «Модели» → «Речь»")
            return
        }
        if (isRecording || isTranscribing) return
        isRecording = true
        scope.launch {
            try {
                val samples = engine.record { level = it }
                isRecording = false
                if (samples.size < 16000 / 4) return@launch
                isTranscribing = true
                val text = engine.recognize(model, samples, 16000)
                if (text.isNotBlank()) onText(text) else toast("Речь не распознана")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Ошибка распознавания: ${e.message}")
            } finally {
                isRecording = false
                isTranscribing = false
                level = 0f
            }
        }
    }

    fun stopDictation() {
        engine.stopRecording()
    }

    // ===================== Озвучка =====================

    fun speak(id: Long, text: String) {
        val model = ttsModel ?: run {
            toast("Установите голос во вкладке «Модели» → «Речь»")
            return
        }
        stopSpeaking()
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        speakingId = id
        speakJob = scope.launch {
            try {
                // Длинный текст озвучиваем по абзацам: первый звук появляется быстрее
                for (part in clean.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }) {
                    engine.speak(model, part, settings().ttsSpeed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Ошибка озвучки: ${e.message}")
            } finally {
                if (speakingId == id) speakingId = -1
            }
        }
    }

    fun stopSpeaking() {
        speakJob?.cancel()
        engine.stopPlayback()
        speakingId = -1
    }

    fun release() {
        engine.release()
    }

    companion object {
        /** Убирает размышления, разметку и эмодзи, которые не нужно читать вслух. */
        fun cleanForSpeech(text: String): String = text
            .replace(Regex("(?s)<think>.*?(</think>|$)"), "")
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_#`>|~]"), "")
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .trim()
    }
}
