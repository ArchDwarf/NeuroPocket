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
import com.neuropocket.app.engine.SamplingParams
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class Role { USER, ASSISTANT }

/** Кнопка-подсказка под сообщением. */
enum class MsgAction { VISION_MODELS, IMAGE_MODELS }

data class Msg(
    val id: Long,
    val role: Role,
    val text: String,
    val attachments: List<DAttachment> = emptyList(),
    val stats: String? = null,
    val isError: Boolean = false,
    /** Картинка, нарисованная в чате. */
    val image: ImageResult? = null,
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
            "Фото или видео", DesktopMedia.IMAGE_EXT + DesktopMedia.VIDEO_EXT, "Изображения и видео",
        )
        if (files.isEmpty()) return
        attachLoading = true
        app.scope.launch {
            for (f in files) {
                try {
                    pending += DesktopMedia.loadAttachment(f, app.settings.visionMaxSide, app.settings.videoFrames)
                } catch (e: Exception) {
                    app.toast("${f.name}: ${e.message}")
                }
            }
            attachLoading = false
        }
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
        (state is LoadState.Ready || app.profiles.any { !it.video })

    fun send() {
        val text = input.trim()
        if ((text.isEmpty() && pending.isEmpty()) || generating) return
        val atts = pending.toList()
        val draw = drawMode || ChatIntent.isDrawRequest(text, atts.isNotEmpty())
        if (!draw && state !is LoadState.Ready) {
            app.toast("Сначала выберите чат-модель вверху или во вкладке «Модели»")
            return
        }
        val prompt = text.ifEmpty {
            if (atts.any { it.kind == DAttachmentKind.VIDEO }) "Опиши, что происходит на видео." else "Опиши, что на изображении."
        }
        messages += Msg(ids.incrementAndGet(), Role.USER, prompt, atts)
        pending.clear()
        input = ""
        drawMode = false
        when {
            draw -> drawInChat(prompt, atts)
            atts.isNotEmpty() && !vision -> messages += Msg(
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
        if (ChatIntent.isDrawRequest(last.text, last.attachments.isNotEmpty())) drawInChat(last.text, last.attachments)
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

    /** Переводит запрос на английский для модели картинок (если загружена чат-модель). */
    private suspend fun toEnglishPrompt(request: String, edit: Boolean): String {
        val stripped = ChatIntent.stripCommand(request)
        if (!ChatIntent.needsTranslation(stripped) || state !is LoadState.Ready) return stripped
        val name = (state as LoadState.Ready).name.lowercase()
        val system = if (edit) {
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
        if (m.attachments.isEmpty() || !vision) return ChatTurn("user", m.text)
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
            }
        }
        sb.append(m.text)
        return ChatTurn("user", sb.toString(), images)
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
                        if (s.systemPrompt.isNotBlank()) add(ChatTurn("system", s.systemPrompt))
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
