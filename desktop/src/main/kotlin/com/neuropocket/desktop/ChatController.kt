package com.neuropocket.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.neuropocket.app.engine.ChatImage
import com.neuropocket.app.engine.ChatIntent
import com.neuropocket.app.engine.ChatTurn
import com.neuropocket.app.engine.GenEvent
import com.neuropocket.app.engine.LlamaEngine
import com.neuropocket.app.engine.LlamaNative
import com.neuropocket.app.engine.Markdown
import com.neuropocket.app.engine.SamplingParams
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class Role { USER, ASSISTANT }

/** Кнопка-подсказка под сообщением. */
enum class MsgAction { VISION_MODELS, IMAGE_MODELS, VIDEO_MODELS, SPEECH_MODELS }

data class Msg(
    val id: Long,
    val role: Role,
    val text: String,
    val attachments: List<DAttachment> = emptyList(),
    val stats: String? = null,
    val isError: Boolean = false,
    /** Картинка, нарисованная в чате. */
    val image: ImageResult? = null,
    /** Видео, созданное в чате. */
    val video: VideoResult? = null,
    /** Служебная подсказка — не попадает в контекст модели. */
    val action: MsgAction? = null,
)

sealed interface LoadState {
    data object None : LoadState
    data class Loading(val name: String) : LoadState
    data class Ready(val name: String, val info: String, val vision: Boolean) : LoadState
    data class Error(val name: String, val message: String) : LoadState
}

class ChatController(private val app: AppState) {
    private val engine = LlamaEngine(NativeDir.dir.absolutePath)
    private val ids = AtomicLong(0)

    var state by mutableStateOf<LoadState>(LoadState.None); private set
    val messages = mutableStateListOf<Msg>()
    val pending = mutableStateListOf<DAttachment>()
    var attachLoading by mutableStateOf(false); private set
    var generating by mutableStateOf(false); private set
    var input by mutableStateOf("")
    /** 🎨 — следующее сообщение рисует модель картинок. */
    var drawMode by mutableStateOf(false)
    private var job: Job? = null
    private var contextStart = 0

    val vision: Boolean get() = (state as? LoadState.Ready)?.vision == true

    fun autoload() {
        val last = app.settings.chatModel
        if (last.isNotBlank() && File(last).exists()) load(File(last))
    }

    fun select(f: File) {
        app.updateSettings(app.settings.copy(chatModel = f.absolutePath))
        load(f)
    }

    fun reloadIfLoaded() {
        if (engine.isLoaded) app.settings.chatModel.takeIf { it.isNotBlank() }?.let { load(File(it)) }
    }

    private fun load(f: File) {
        app.scope.launch {
            stop()
            job?.join()
            state = LoadState.Loading(f.name)
            state = try {
                val s = app.settings
                val loaded = engine.load(
                    f.absolutePath, app.mmprojFor(f)?.absolutePath, s.contextSize, s.threads,
                    placement = Placement.chat(s, app.devices),
                )
                contextStart = 0
                LoadState.Ready(f.name, loaded.info, loaded.hasVision)
            } catch (e: Throwable) {
                LoadState.Error(f.name, e.message ?: e.toString())
            }
        }
    }

    suspend fun systemInfo(): String = runCatching { engine.systemInfo() }.getOrDefault("")

    suspend fun listDevices() = engine.listDevices()

    // ===================== Вложения =====================

    fun attachFromDialog() {
        val files = DesktopMedia.chooseFiles(
            "Фото, видео, файлы, документы, аудио",
            DesktopMedia.IMAGE_EXT + DesktopMedia.VIDEO_EXT + DesktopMedia.TEXT_EXT + DesktopMedia.DOC_EXT + DesktopMedia.AUDIO_EXT,
            "Всё поддерживаемое",
        )
        attachFiles(files)
    }

    /** Прикрепить файлы (из диалога или перетаскиванием). */
    fun attachFiles(files: List<File>) {
        if (files.isEmpty()) return
        attachLoading = true
        app.scope.launch {
            for (f in files) {
                try {
                    var a = DesktopMedia.loadAttachment(f, app.settings.visionMaxSide, app.settings.videoFrames)
                    if (a.kind == DAttachmentKind.AUDIO) a = transcribeAttachment(a) ?: continue
                    pending += a
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    app.toast("${f.name}: ${e.message}")
                }
            }
            attachLoading = false
        }
    }

    private suspend fun transcribeAttachment(a: DAttachment): DAttachment? {
        val (samples, rate) = a.audio ?: return a
        val text = app.speech.transcribe(samples, rate)
        if (text == null) {
            messages += Msg(
                ids.incrementAndGet(), Role.ASSISTANT,
                "Чтобы понимать голосовые записи, установите модель распознавания речи во вкладке «Речь» " +
                    "(например Whisper или Vosk для русского) — запись расшифруется прямо на компьютере.",
                action = MsgAction.SPEECH_MODELS,
            )
            return null
        }
        if (text.isBlank()) {
            app.toast("${a.name}: речь не распознана")
            return null
        }
        return DAttachment(a.id, a.kind, a.name, a.thumbnail, emptyList(), a.durationSec, text = text.trim())
    }

    // ===================== Сообщения =====================

    /** Прикрепить нарисованную картинку к следующему сообщению (чтобы изменить её). */
    fun attachResult(r: ImageResult) {
        val img = DesktopMedia.scaleToMax(r.image, app.settings.visionMaxSide)
        pending += DAttachment(
            java.util.UUID.randomUUID().toString(), DAttachmentKind.IMAGE, r.file.name,
            DesktopMedia.scaleToMax(img, 256).toComposeImageBitmap(), listOf(DFrame(DesktopMedia.toRgb(img), img.width, img.height)),
        )
        drawMode = true
    }

    val inputEnabled: Boolean get() = state !is LoadState.Loading &&
        (state is LoadState.Ready || app.profiles.isNotEmpty())

    fun send() {
        val text = input.trim()
        if ((text.isEmpty() && pending.isEmpty()) || generating) return
        val atts = pending.toList()
        val visual = atts.filter { it.kind == DAttachmentKind.IMAGE || it.kind == DAttachmentKind.VIDEO }
        val makeVideo = !drawMode && ChatIntent.isVideoRequest(text)
        val draw = !makeVideo && (drawMode || ChatIntent.isDrawRequest(text, visual.isNotEmpty()))
        if (!draw && !makeVideo && state !is LoadState.Ready) {
            app.toast("Сначала выберите чат-модель вверху или во вкладке «Модели»")
            return
        }
        val prompt = text.ifEmpty {
            when {
                atts.any { it.kind == DAttachmentKind.VIDEO } -> "Опиши, что происходит на видео."
                atts.any { it.kind == DAttachmentKind.IMAGE } -> "Опиши, что на изображении."
                atts.any { it.kind == DAttachmentKind.FILE } -> "Разбери этот файл: кратко опиши, что в нём, и укажи проблемы, если они есть."
                else -> "Ответь на голосовое сообщение."
            }
        }
        messages += Msg(ids.incrementAndGet(), Role.USER, prompt, atts)
        pending.clear()
        input = ""
        drawMode = false
        when {
            makeVideo -> videoInChat(prompt, visual)
            draw -> drawInChat(prompt, visual)
            visual.isNotEmpty() && !vision -> messages += Msg(
                ids.incrementAndGet(), Role.ASSISTANT,
                "Модель «${(state as? LoadState.Ready)?.name ?: ""}» понимает только текст и не видит фото.\n\n" +
                    "• Чтобы описать фото или ответить на вопрос о нём, выберите модель со значком 👁 " +
                    "(например Qwen2.5-VL 7B или Gemma 3 12B) — модуль зрения скачается вместе с ней.\n" +
                    "• Чтобы изменить фото, напишите, что сделать: «перерисуй в стиле аниме», «сделай зимним» — " +
                    "или включите 🎨 перед отправкой.",
                action = MsgAction.VISION_MODELS,
            )
            else -> generate()
        }
    }

    fun regenerate() {
        if (generating) return
        if (messages.lastOrNull()?.role == Role.ASSISTANT) messages.removeAt(messages.lastIndex)
        val last = messages.lastOrNull() ?: return
        if (last.role != Role.USER) return
        val visual = last.attachments.filter { it.kind == DAttachmentKind.IMAGE || it.kind == DAttachmentKind.VIDEO }
        if (ChatIntent.isVideoRequest(last.text)) videoInChat(last.text, visual)
        else if (ChatIntent.isDrawRequest(last.text, visual.isNotEmpty())) drawInChat(last.text, visual)
        else if (state is LoadState.Ready) generate()
    }

    // ===================== Рисование в чате =====================

    private fun drawInChat(request: String, atts: List<DAttachment>) {
        val replyId = ids.incrementAndGet()
        val src = atts.firstOrNull()?.frames?.firstOrNull()?.let { DesktopMedia.fromRgb(it.rgb, it.width, it.height) }
        if (app.profiles.none { !it.video }) {
            messages += Msg(
                replyId, Role.ASSISTANT,
                "Чтобы рисовать" + (if (src != null) " и изменять фото" else "") + ", нужна модель для картинок. " +
                    "Скачайте набор во вкладке «Модели» → «Картинки и видео» — например Z-Image Turbo или FLUX.2 klein.",
                action = MsgAction.IMAGE_MODELS,
            )
            return
        }
        messages += Msg(replyId, Role.ASSISTANT, if (src == null) "🎨 Рисую…" else "🎨 Изменяю фото…", stats = "подготовка")
        generating = true
        job = app.scope.launch {
            try {
                update(replyId) { it.copy(stats = "перевожу описание для модели картинок") }
                val en = toEnglishPrompt(request, edit = src != null)
                update(replyId) { it.copy(stats = "загрузка модели картинок") }
                app.images.awaitReady()?.let { err ->
                    update(replyId) { it.copy(text = "Не удалось загрузить модель картинок: $err", isError = true, stats = null) }
                    return@launch
                }
                val r = app.images.generateForChat(en, src) { st, total ->
                    update(replyId) { it.copy(stats = "шаг $st из $total") }
                }
                if (r == null) {
                    update(replyId) { it.copy(text = "Рисование остановлено.", stats = null) }
                } else {
                    update(replyId) {
                        it.copy(
                            text = (if (src == null) "Готово!" else "Готово — вот изменённое фото.") + " Описание для модели: «$en»",
                            image = r,
                            stats = String.format(Locale.US, "%s · %.1f с", app.images.archName, r.seconds),
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                update(replyId) { it.copy(text = "Не удалось нарисовать: ${e.message}", isError = true, stats = null) }
            } finally {
                generating = false
            }
        }
    }

    // ===================== Видео в чате =====================

    private fun videoInChat(request: String, atts: List<DAttachment>) {
        val replyId = ids.incrementAndGet()
        val src = atts.firstOrNull()?.frames?.firstOrNull()?.let { DesktopMedia.fromRgb(it.rgb, it.width, it.height) }
        if (app.video.profiles.isEmpty()) {
            messages += Msg(
                replyId, Role.ASSISTANT,
                "Чтобы создавать видео" + (if (src != null) " и оживлять фото" else "") + ", нужна видеомодель. " +
                    "Скачайте набор Wan во вкладке «Модели» → «Картинки и видео» (Wan 2.1 1.3B — для начала, " +
                    "Wan 2.2 TI2V 5B — лучше качество и умеет оживлять фото). Видео считается долго: минуты даже на хорошей видеокарте.",
                action = MsgAction.VIDEO_MODELS,
            )
            return
        }
        messages += Msg(replyId, Role.ASSISTANT, if (src == null) "🎬 Создаю видео…" else "🎬 Оживляю фото…", stats = "подготовка")
        generating = true
        job = app.scope.launch {
            try {
                update(replyId) { it.copy(stats = "готовлю описание для видеомодели") }
                val en = toEnglishPrompt(request, edit = false, video = true, hasSource = src != null)
                update(replyId) { it.copy(stats = "загрузка видеомодели") }
                app.video.awaitReady()?.let { err ->
                    update(replyId) { it.copy(text = "Не удалось загрузить видеомодель: $err", isError = true, stats = null) }
                    return@launch
                }
                val started = System.currentTimeMillis()
                val r = app.video.generateVideoForChat(en, src) { st, total ->
                    val sec = (System.currentTimeMillis() - started) / 1000
                    val eta = if (st > 0) " · осталось ≈${(sec * (total - st) / st).coerceAtLeast(0)} с" else ""
                    update(replyId) { it.copy(stats = "шаг $st из $total$eta") }
                }
                if (r == null) {
                    update(replyId) { it.copy(text = "Создание видео остановлено.", stats = null) }
                } else {
                    update(replyId) {
                        it.copy(
                            text = "Готово! Описание для модели: «$en»",
                            video = r,
                            stats = String.format(Locale.US, "%s · %d кадров · %.0f с", app.video.archName, r.frames.size, r.seconds),
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                update(replyId) { it.copy(text = "Не удалось создать видео: ${e.message}", isError = true, stats = null) }
            } finally {
                generating = false
            }
        }
    }

    /** Переводит запрос на английский для модели картинок/видео (если загружена чат-модель). */
    private suspend fun toEnglishPrompt(request: String, edit: Boolean, video: Boolean = false, hasSource: Boolean = false): String {
        val stripped = if (video) ChatIntent.stripVideoCommand(request) else ChatIntent.stripCommand(request)
        val motion = "natural smooth motion, subtle camera movement, cinematic, high quality"
        if (state !is LoadState.Ready) {
            return if (video && hasSource && ChatIntent.needsTranslation(stripped)) motion else stripped
        }
        if (!video && !ChatIntent.needsTranslation(stripped)) return stripped
        val name = (state as LoadState.Ready).name.lowercase()
        val system = if (video) {
            "You turn a user's request into a prompt for a text-to-video model. Reply with one line only: " +
                "a concise English description of the scene and the motion (subject, action, camera movement, style). " +
                (if (hasSource) "The video starts from the user's photo; if the request only asks to animate it, " +
                    "describe natural, subtle motion of what is likely in a photo. " else "") +
                "No explanations, no quotes."
        } else if (edit) {
            "You turn a user's photo editing request into a short English instruction for an image editing model. " +
                "Reply with the instruction only: one line, no quotes, no explanations."
        } else {
            "You turn a user's request into a prompt for an image generator. Reply with one line only: " +
                "a concise English prompt of comma-separated descriptive phrases (subject, details, style, lighting). " +
                "No explanations, no quotes."
        }
        val sb = StringBuilder()
        engine.generate(
            listOf(ChatTurn("system", system), ChatTurn("user", stripped + if ("qwen3" in name) " /no_think" else "")),
            SamplingParams(temperature = 0.3f, topP = 0.9f, topK = 40, repeatPenalty = 1.05f, maxTokens = 200),
        ).collect { ev -> if (ev is GenEvent.Text) sb.append(ev.text) }
        return ChatIntent.cleanPrompt(sb.toString()) ?: stripped
    }

    private fun toTurn(m: Msg): ChatTurn {
        if (m.role == Role.ASSISTANT) return ChatTurn("assistant", m.text)
        if (m.attachments.isEmpty()) return ChatTurn("user", m.text)
        if (!vision) {
            val texts = m.attachments.filter { it.kind == DAttachmentKind.FILE || it.kind == DAttachmentKind.AUDIO }
            return ChatTurn("user", texts.joinToString("") { textPart(it) } + m.text)
        }
        val marker = engine.marker
        val images = mutableListOf<ChatImage>()
        val sb = StringBuilder()
        for (a in m.attachments) {
            when (a.kind) {
                DAttachmentKind.IMAGE -> {
                    val f = a.frames.first()
                    images += ChatImage(a.id, f.rgb, f.width, f.height)
                    sb.append(marker).append('\n')
                }
                DAttachmentKind.VIDEO -> {
                    sb.append("Видео «${a.name}», ${a.durationSec.toInt()} с, ${a.frames.size} кадров:\n")
                    a.frames.forEachIndexed { i, f ->
                        images += ChatImage("${a.id}#$i", f.rgb, f.width, f.height)
                        sb.append('[').append(f.label).append("] ").append(marker).append('\n')
                    }
                }
                DAttachmentKind.FILE, DAttachmentKind.AUDIO -> sb.append(textPart(a))
            }
        }
        sb.append(m.text)
        return ChatTurn("user", sb.toString(), images)
    }

    private fun textPart(a: DAttachment): String = if (a.kind == DAttachmentKind.AUDIO) {
        "Голосовое сообщение «${a.name}» (расшифровка): ${a.text}\n\n"
    } else {
        val lang = Markdown.languageFor(a.name.substringAfterLast('.', ""))
        "Файл «${a.name}»:\n```$lang\n${a.text}\n```\n\n"
    }

    private fun generate() {
        val replyId = ids.incrementAndGet()
        messages += Msg(replyId, Role.ASSISTANT, "")
        generating = true
        job = app.scope.launch {
            val s = app.settings
            val params = SamplingParams(s.temperature, s.topP, s.topK, s.repeatPenalty, s.maxTokens)
            val sb = StringBuilder()
            var note: String? = null
            try {
                while (true) {
                    val turns = buildList {
                        val sys = ChatPresets.systemPrompt(s)
                        if (sys.isNotBlank()) add(ChatTurn("system", sys))
                        messages.drop(contextStart).filter { it.id != replyId && !it.isError && it.action == null }.forEach { add(toTurn(it)) }
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
                            val lastUser = messages.indexOfLast { it.role == Role.USER }
                            if (contextStart < lastUser) {
                                contextStart = (contextStart + 2).coerceAtMost(lastUser)
                                note = "старые сообщения забыты: не хватает контекста"
                                continue
                            }
                            update(replyId) {
                                it.copy(text = "Сообщение не помещается в контекст. Увеличьте размер контекста в настройках.", isError = true)
                            }
                        }
                        d.code == LlamaNative.RES_CONTEXT_FULL -> note = "контекст заполнен — очистите чат"
                        d.code == LlamaNative.RES_MEDIA_ERROR -> update(replyId) { it.copy(text = "Не удалось обработать изображение.", isError = true) }
                        d.code < 0 -> update(replyId) { it.copy(text = "Ошибка генерации (код ${d.code})", isError = true) }
                        else -> {
                            val sec = d.millis / 1000.0
                            val stats = String.format(Locale.US, "%d ток. · %.1f с · %.1f ток/с", d.code, sec, if (sec > 0) d.code / sec else 0.0)
                            update(replyId) { it.copy(stats = listOfNotNull(stats, note).joinToString(" · ")) }
                            note = null
                            if (s.autoSpeak) app.speech.speak(replyId, sb.toString())
                        }
                    }
                    note?.let { n -> update(replyId) { it.copy(stats = n) } }
                    break
                }
                if (sb.isEmpty() && messages.find { it.id == replyId }?.isError == false) {
                    update(replyId) { it.copy(text = "…", stats = "остановлено") }
                }
            } finally {
                generating = false
            }
        }
    }

    private inline fun update(id: Long, f: (Msg) -> Msg) {
        val i = messages.indexOfLast { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    fun stop() {
        if (generating) {
            engine.stop()
            app.images.cancel()
            app.video.cancel()
        }
    }

    fun clear() {
        app.scope.launch {
            stop()
            job?.join()
            app.speech.stopSpeaking()
            messages.clear()
            pending.clear()
            contextStart = 0
            engine.resetChat()
        }
    }

    fun shutdown() {
        engine.stop()
        runBlocking { engine.unload() }
    }
}
