package com.neuropocket.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import com.neuropocket.app.data.Attachment
import com.neuropocket.app.data.AttachmentKind
import com.neuropocket.app.data.ModelKind
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel, modifier: Modifier, onOpenModels: (ModelKind) -> Unit) {
    val state = vm.modelState
    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text("NeuroPocket", style = MaterialTheme.typography.titleMedium)
                    val sub = when (state) {
                        is ModelState.Ready -> (if (state.hasVision) "👁 " else "") + state.name
                        is ModelState.Loading -> "Загрузка ${state.name}…"
                        is ModelState.Error -> "Ошибка загрузки"
                        ModelState.None -> "Модель не выбрана"
                    }
                    Text(
                        sub, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = vm::regenerate,
                    enabled = !vm.isGenerating && vm.messages.isNotEmpty(),
                ) { Icon(Icons.Default.Refresh, "Перегенерировать") }
                IconButton(onClick = vm::clearChat, enabled = vm.messages.isNotEmpty()) {
                    Icon(Icons.Default.Delete, "Очистить чат")
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.messages.isNotEmpty() -> MessageList(vm, onOpenModels)
                state !is ModelState.Ready -> ModelPlaceholder(state, { onOpenModels(ModelKind.CHAT) }, vm::reloadModel)
                else -> Text(
                    "Модель работает прямо на телефоне — без интернета.\n" +
                        "Напишите что-нибудь, прикрепите фото (＋) или попросите нарисовать картинку («нарисуй кота») — " +
                        "либо включите 🎨.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        InputBar(
            vm = vm,
            enabled = vm.inputEnabled,
            generating = vm.isGenerating,
        )
    }
}

@Composable
private fun ModelPlaceholder(state: ModelState, onOpenModels: () -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is ModelState.Loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Загружаем модель в память…", textAlign = TextAlign.Center)
            }
            is ModelState.Error -> {
                Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("Повторить") }
                TextButton(onClick = onOpenModels) { Text("Выбрать другую модель") }
            }
            else -> {
                Text(
                    "Скачайте или выберите модель, чтобы начать общение.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenModels) { Text("Перейти к моделям") }
            }
        }
    }
}

@Composable
private fun MessageList(vm: MainViewModel, onOpenModels: (ModelKind) -> Unit) {
    val listState = rememberLazyListState()
    val last = vm.messages.lastOrNull()
    LaunchedEffect(vm.messages.size, last?.text?.length) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex, Int.MAX_VALUE)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(vm.messages, key = { it.id }) { msg ->
            val typing = vm.isGenerating && msg.id == last?.id
            MessageBubble(
                msg, typing = typing,
                speaking = vm.speech.speakingId == msg.id,
                onSpeak = if (!typing && msg.role == Role.ASSISTANT && !msg.isError && msg.text.isNotBlank()) {
                    { if (vm.speech.speakingId == msg.id) vm.speech.stopSpeaking() else vm.speech.speak(msg.id, msg.text) }
                } else null,
                onSave = { bmp -> vm.studio.save(bmp) },
                onEditFurther = { bmp -> vm.attachBitmap(bmp); vm.drawMode = true },
                onAction = { a -> onOpenModels(if (a == ChatAction.IMAGE_MODELS) ModelKind.IMAGE else ModelKind.CHAT) },
            )
        }
    }
}

/** Делит ответ на «размышления» (<think>…</think>) и основной текст. */
private fun splitThinking(text: String): Pair<String?, String> {
    val start = text.indexOf("<think>")
    if (start < 0) return null to text
    val end = text.indexOf("</think>", start)
    return if (end < 0) {
        text.substring(start + 7).trim() to ""
    } else {
        text.substring(start + 7, end).trim() to (text.substring(0, start) + text.substring(end + 8)).trim()
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    typing: Boolean,
    speaking: Boolean = false,
    onSpeak: (() -> Unit)? = null,
    onSave: (android.graphics.Bitmap) -> Unit = {},
    onEditFurther: (android.graphics.Bitmap) -> Unit = {},
    onAction: (ChatAction) -> Unit = {},
) {
    val isUser = msg.role == Role.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when {
                msg.isError -> MaterialTheme.colorScheme.errorContainer
                isUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (isUser) {
                    if (msg.attachments.isNotEmpty()) {
                        Row(
                            Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            msg.attachments.forEach { AttachmentThumb(it, size = 96) }
                        }
                    }
                    SelectionContainer { Text(msg.text) }
                } else {
                    val (thinking, answer) = splitThinking(msg.text)
                    if (!thinking.isNullOrBlank()) {
                        var expanded by rememberSaveable(msg.id) { mutableStateOf(false) }
                        Text(
                            if (expanded) "💭 $thinking" else "💭 Размышления… (нажмите, чтобы раскрыть)",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .clickable { expanded = !expanded },
                        )
                    }
                    msg.image?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Нарисованное изображение",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth()
                                .aspectRatio(bmp.width.toFloat() / bmp.height)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Row {
                            TextButton(onClick = { onSave(bmp) }) { Text("💾 Сохранить") }
                            TextButton(onClick = { onEditFurther(bmp) }) { Text("✏ Изменить") }
                        }
                    }
                    when {
                        answer.isNotEmpty() -> SelectionContainer { Text(answer) }
                        typing -> TypingDots()
                    }
                    if (typing && msg.text.startsWith("🎨")) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    msg.action?.let { a ->
                        FilledTonalButton(onClick = { onAction(a) }, modifier = Modifier.padding(top = 6.dp)) {
                            Text(if (a == ChatAction.IMAGE_MODELS) "Скачать модель для картинок" else "Выбрать модель со зрением 👁")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        msg.stats?.let {
                            Text(
                                it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false).padding(top = 6.dp),
                            )
                        }
                        if (onSpeak != null) {
                            Text(
                                if (speaking) "  ⏹ стоп" else "  🔊",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp).clickable(onClick = onSpeak),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
        Text("думает…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AttachmentThumb(a: Attachment, size: Int, onRemove: (() -> Unit)? = null) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp))) {
        Image(
            bitmap = a.thumbnail.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (a.kind == AttachmentKind.VIDEO) {
            Text(
                "▶ ${a.durationSec.toInt()} с · ${a.frames.size} кадр.",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        if (onRemove != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp)
                    .clip(RoundedCornerShape(11.dp)).background(Color(0x99000000))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, "Убрать", tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun MicButton(vm: MainViewModel, enabled: Boolean, onText: (String) -> Unit) {
    val sp = vm.speech
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sp.startDictation(onText)
    }
    when {
        sp.isTranscribing -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        sp.isRecording -> FilledIconButton(
            onClick = sp::stopDictation,
            modifier = Modifier.padding(bottom = 4.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            // Пульсирующий кружок — уровень громкости
            Box(
                Modifier.size((10 + 14 * sp.level.coerceIn(0f, 1f)).dp)
                    .background(MaterialTheme.colorScheme.onError, RoundedCornerShape(50))
            )
        }
        else -> IconButton(
            onClick = {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) sp.startDictation(onText) else permission.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            enabled = enabled,
            modifier = Modifier.padding(bottom = 4.dp),
        ) { Text("🎤", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun InputBar(vm: MainViewModel, enabled: Boolean, generating: Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris -> if (uris.isNotEmpty()) vm.attach(uris) }

    Surface(tonalElevation = 3.dp) {
        Column {
            if (vm.pendingAttachments.isNotEmpty() || vm.attachLoading) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    vm.pendingAttachments.forEach { a ->
                        AttachmentThumb(a, size = 72) { vm.removeAttachment(a) }
                    }
                    if (vm.attachLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    enabled = enabled && !generating,
                    modifier = Modifier.padding(bottom = 4.dp).size(40.dp),
                ) {
                    Icon(Icons.Default.Add, "Прикрепить фото или видео", tint = MaterialTheme.colorScheme.primary)
                }
                // 🎨 — следующее сообщение рисует модель картинок
                Box(
                    Modifier.padding(bottom = 8.dp).size(36.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (vm.drawMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable(enabled = enabled && !generating) { vm.drawMode = !vm.drawMode },
                    contentAlignment = Alignment.Center,
                ) { Text("🎨", style = MaterialTheme.typography.titleMedium) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                !enabled -> "Модель не загружена"
                                vm.drawMode && vm.pendingAttachments.isNotEmpty() -> "Что изменить на фото?"
                                vm.drawMode -> "Что нарисовать?"
                                else -> "Сообщение"
                            }
                        )
                    },
                    enabled = enabled,
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.size(4.dp))
                MicButton(vm, enabled = enabled && !generating) { spoken ->
                    text = if (text.isBlank()) spoken else text.trimEnd() + " " + spoken
                }
                Spacer(Modifier.size(4.dp))
                if (generating) {
                    FilledIconButton(onClick = vm::stopGeneration, modifier = Modifier.padding(bottom = 4.dp)) {
                        // «Стоп» — белый квадрат
                        Box(
                            Modifier.size(14.dp).background(
                                MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp)
                            )
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = { vm.send(text); text = "" },
                        enabled = enabled && !vm.attachLoading &&
                            (text.isNotBlank() || vm.pendingAttachments.isNotEmpty()),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
                }
            }
        }
    }
}
