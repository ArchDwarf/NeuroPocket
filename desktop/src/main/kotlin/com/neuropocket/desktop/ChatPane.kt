package com.neuropocket.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ChatPane(app: AppState) {
    val c = app.chat
    Column(Modifier.fillMaxSize()) {
        // ---------- Шапка ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Чат", style = MaterialTheme.typography.titleLarge)
                val sub = when (val s = c.state) {
                    is LoadState.Ready -> (if (s.vision) "👁 " else "") + s.info.replace("\n", " · ")
                    is LoadState.Loading -> "Загрузка ${s.name}…"
                    is LoadState.Error -> "Ошибка: ${s.message}"
                    LoadState.None -> "Модель не выбрана"
                }
                Hint(sub)
            }
            val current = app.settings.chatModel.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.exists() }
            val models = app.chatModels + listOfNotNull(current?.takeIf { cur -> app.chatModels.none { it.absolutePath == cur.absolutePath } })
            Dropdown(
                "Выбрать модель", models,
                models.find { it.absolutePath == app.settings.chatModel },
                { (if (app.mmprojFor(it) != null) "👁 " else "") + it.name },
                Modifier.widthIn(min = 260.dp, max = 420.dp),
            ) { c.select(it) }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = c::regenerate, enabled = !c.generating && c.messages.isNotEmpty()) { Text("↻ Заново") }
            TextButton(onClick = c::clear, enabled = c.messages.isNotEmpty()) { Text("🗑 Очистить") }
        }

        // ---------- Сообщения ----------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                c.state is LoadState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Загружаем модель…")
                }
                c.state !is LoadState.Ready && c.messages.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Выберите чат-модель вверху или скачайте её во вкладке «Модели».", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { app.tab = Tab.MODELS }) { Text("Перейти к моделям") }
                }
                c.messages.isEmpty() -> Text(
                    "Модель работает на вашем компьютере — без интернета.\n" +
                        (if (c.vision) "Можно прикрепить фото и видео (📎)." else "") +
                        "\nEnter — отправить, Shift+Enter — новая строка.",
                    Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> MessageList(app)
            }
        }

        // ---------- Ввод ----------
        InputArea(app)
    }
}

@Composable
private fun MessageList(app: AppState) {
    val c = app.chat
    val list = rememberLazyListState()
    val last = c.messages.lastOrNull()
    LaunchedEffect(c.messages.size, last?.text?.length) {
        if (c.messages.isNotEmpty()) list.scrollToItem(c.messages.lastIndex, Int.MAX_VALUE)
    }
    LazyColumn(
        state = list, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(c.messages, key = { it.id }) { m ->
            Bubble(app, m, typing = c.generating && m.id == last?.id)
        }
    }
}

private fun splitThinking(text: String): Pair<String?, String> {
    val start = text.indexOf("<think>")
    if (start < 0) return null to text
    val end = text.indexOf("</think>", start)
    return if (end < 0) text.substring(start + 7).trim() to ""
    else text.substring(start + 7, end).trim() to (text.substring(0, start) + text.substring(end + 8)).trim()
}

@Composable
private fun Bubble(app: AppState, m: Msg, typing: Boolean) {
    val user = m.role == Role.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = when {
                m.isError -> MaterialTheme.colorScheme.errorContainer
                user -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 820.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (m.attachments.isNotEmpty()) {
                    Row(Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.attachments.forEach { Thumb(it, 120) }
                    }
                }
                if (user) {
                    SelectionContainer { Text(m.text) }
                } else {
                    val (thinking, answer) = splitThinking(m.text)
                    if (!thinking.isNullOrBlank()) {
                        var open by remember(m.id) { mutableStateOf(false) }
                        Text(
                            if (open) "💭 $thinking" else "💭 Размышления… (нажмите, чтобы раскрыть)",
                            style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp).clickable { open = !open },
                        )
                    }
                    when {
                        answer.isNotEmpty() -> SelectionContainer { Text(answer) }
                        typing -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("думает…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        m.stats?.let { Hint(it) }
                        if (!typing && !m.isError && m.text.isNotBlank()) {
                            val speaking = app.speech.speakingId == m.id
                            Text(
                                if (speaking) "   ⏹ стоп" else "   🔊 озвучить",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    if (speaking) app.speech.stopSpeaking() else app.speech.speak(m.id, m.text)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(a: DAttachment, size: Int, onRemove: (() -> Unit)? = null) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp))) {
        Image(a.thumbnail, a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (a.kind == DAttachmentKind.VIDEO) {
            Text(
                "▶ ${a.durationSec.toInt()} с · ${a.frames.size} кадр.", color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(3.dp),
            )
        }
        if (onRemove != null) {
            Text(
                " ✕ ", color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .clickable(onClick = onRemove),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InputArea(app: AppState) {
    val c = app.chat
    val sp = app.speech
    val ready = c.state is LoadState.Ready
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (c.pending.isNotEmpty() || c.attachLoading) {
                Row(
                    Modifier.padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    c.pending.forEach { a -> Thumb(a, 80) { c.pending.remove(a) } }
                    if (c.attachLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                TextButton(onClick = c::attachFromDialog, enabled = ready && !c.generating) {
                    Text(if (c.vision) "📎" else "📎", fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = c.input,
                    onValueChange = { c.input = it },
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                            c.send(); true
                        } else false
                    },
                    placeholder = { Text(if (ready) "Сообщение" else "Модель не загружена") },
                    enabled = ready,
                    maxLines = 8,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                when {
                    sp.transcribing -> CircularProgressIndicator(Modifier.size(28.dp).padding(4.dp), strokeWidth = 2.dp)
                    else -> TextButton(onClick = {
                        sp.toggleDictation { spoken -> c.input = if (c.input.isBlank()) spoken else c.input.trimEnd() + " " + spoken }
                    }, enabled = !c.generating) {
                        Text(if (sp.recording) "⏹ ${"▮".repeat((sp.level * 6).toInt().coerceIn(1, 6))}" else "🎤")
                    }
                }
                Spacer(Modifier.size(6.dp))
                if (c.generating) {
                    FilledTonalButton(onClick = c::stop) { Text("■ Стоп") }
                } else {
                    Button(onClick = c::send, enabled = ready && !c.attachLoading && (c.input.isNotBlank() || c.pending.isNotEmpty())) {
                        Text("Отправить")
                    }
                }
            }
        }
    }
}
