package com.neuropocket.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neuropocket.speech.HttpDownloader
import com.neuropocket.speech.SpeechCatalogItem
import com.neuropocket.speech.SpeechKind
import com.neuropocket.speech.SpeechModel
import com.neuropocket.speech.Wav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

data class SpeechInstall(val title: String, val stage: String, val progress: Float)

/** Речь на компьютере: модели, диктовка, расшифровка файлов, озвучка. */
class SpeechController(private val app: AppState) {
    private val engine = DesktopSpeechEngine { app.settings.threads }

    var installed by mutableStateOf<List<SpeechModel>>(emptyList()); private set
    val installing = mutableStateMapOf<String, SpeechInstall>()
    private val jobs = mutableMapOf<String, Job>()

    var recording by mutableStateOf(false); private set
    var transcribing by mutableStateOf(false); private set
    var level by mutableStateOf(0f); private set
    var speakingId by mutableStateOf(-1L); private set
    private var speakJob: Job? = null

    /** Поле «Речь»: результат расшифровки и текст для озвучки. */
    var transcript by mutableStateOf("")
    var ttsText by mutableStateOf("Привет! Я говорю прямо на твоём компьютере, без интернета.")
    var speaker by mutableStateOf(0)
    var lastAudio: Pair<FloatArray, Int>? = null; private set

    fun refresh() {
        installed = SpeechModel.scan(Paths.speechModels)
    }

    val asr: SpeechModel? get() = installed.find { it.kind == SpeechKind.ASR && it.dir.path == app.settings.asrModelDir }
    val tts: SpeechModel? get() = installed.find { it.kind == SpeechKind.TTS && it.dir.path == app.settings.ttsModelDir }

    fun isInstalled(item: SpeechCatalogItem) = installed.any { it.dir.name == item.dirName }

    fun select(m: SpeechModel) {
        val s = app.settings
        app.updateSettings(if (m.kind == SpeechKind.ASR) s.copy(asrModelDir = m.dir.path) else s.copy(ttsModelDir = m.dir.path))
    }

    fun install(url: String, title: String = url.substringAfterLast('/')) {
        if (!url.startsWith("https://") || !url.endsWith(".tar.bz2")) {
            app.toast("Нужна ссылка на архив модели sherpa-onnx (.tar.bz2)")
            return
        }
        if (url in installing) return
        installing[url] = SpeechInstall(title, "Загрузка", 0f)
        jobs[url] = app.scope.launch {
            try {
                val m = HttpDownloader.installSpeechModel(url, Paths.speechModels) { stage, p ->
                    installing[url] = SpeechInstall(title, stage, p)
                }
                refresh()
                if (m.kind == SpeechKind.ASR && asr == null) select(m)
                if (m.kind == SpeechKind.TTS && tts == null) select(m)
                app.toast("Установлено: ${m.title}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.toast("Ошибка установки: ${e.message}")
            } finally {
                installing.remove(url)
                jobs.remove(url)
            }
        }
    }

    fun cancelInstall(url: String) {
        jobs[url]?.cancel()
    }

    fun delete(m: SpeechModel) {
        app.scope.launch {
            withContext(Dispatchers.IO) { m.dir.deleteRecursively() }
            refresh()
        }
    }

    // ===================== Распознавание =====================

    /** Диктовка: запись с микрофона до повторного нажатия, затем распознавание. */
    fun toggleDictation(onText: (String) -> Unit) {
        if (recording) {
            engine.stopRecording()
            return
        }
        val model = asr ?: run {
            app.toast("Установите модель распознавания речи (вкладка «Речь»)")
            return
        }
        if (transcribing) return
        recording = true
        app.scope.launch {
            try {
                val samples = engine.record { level = it }
                recording = false
                if (samples.size < 4000) return@launch
                transcribing = true
                val text = engine.recognize(model, samples, 16000)
                if (text.isNotBlank()) onText(text) else app.toast("Речь не распознана")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.toast("Ошибка: ${e.message}")
            } finally {
                recording = false
                transcribing = false
                level = 0f
            }
        }
    }

    /** Расшифровка звука для чата; null — модель распознавания не установлена. */
    suspend fun transcribe(samples: FloatArray, rate: Int): String? {
        val model = asr ?: return null
        transcribing = true
        try {
            return engine.recognize(model, samples, rate)
        } finally {
            transcribing = false
        }
    }

    /** Расшифровка аудиофайла (WAV). */
    fun transcribeFile() {
        val model = asr ?: run {
            app.toast("Установите модель распознавания речи")
            return
        }
        val f = DesktopMedia.chooseFile("Аудиофайл", DesktopMedia.AUDIO_EXT, "WAV") ?: return
        transcribing = true
        app.scope.launch {
            try {
                val (samples, rate) = withContext(Dispatchers.IO) { Wav.read(f) }
                transcript = engine.recognize(model, samples, rate)
                if (transcript.isBlank()) app.toast("Речь не распознана")
            } catch (e: Throwable) {
                app.toast("Ошибка: ${e.message}")
            } finally {
                transcribing = false
            }
        }
    }

    // ===================== Синтез =====================

    fun speak(id: Long, text: String) {
        val model = tts ?: run {
            app.toast("Установите голос (вкладка «Речь»)")
            return
        }
        stopSpeaking()
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        speakingId = id
        speakJob = app.scope.launch {
            try {
                for (part in clean.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }) {
                    val audio = engine.synthesize(model, part, app.settings.ttsSpeed, speaker)
                    lastAudio = audio
                    engine.play(audio.first, audio.second)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.toast("Ошибка озвучки: ${e.message}")
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

    /** Озвучивает текст и сохраняет WAV. */
    fun saveSpeech() {
        val model = tts ?: run {
            app.toast("Установите голос")
            return
        }
        val text = cleanForSpeech(ttsText)
        if (text.isBlank()) return
        val name = "speech_${SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())}.wav"
        val f = DesktopMedia.chooseSave("Сохранить речь", name) ?: return
        app.scope.launch {
            try {
                val (samples, rate) = engine.synthesize(model, text, app.settings.ttsSpeed, speaker)
                withContext(Dispatchers.IO) { Wav.write(f, samples, rate) }
                app.toast("Сохранено: ${f.name}")
            } catch (e: Throwable) {
                app.toast("Ошибка: ${e.message}")
            }
        }
    }

    fun numSpeakers(): Int = engine.numSpeakers()

    fun shutdown() {
        runBlocking { stopSpeaking() }
        engine.release()
    }

    companion object {
        fun cleanForSpeech(text: String): String = text
            .replace(Regex("(?s)<think>.*?(</think>|$)"), "")
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_#`>|~]"), "")
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .trim()
    }
}
