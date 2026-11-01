package com.neuropocket.app.ui

import androidx.compose.foundation.background
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
fun ChatScreen(vm: MainViewModel, modifier: Modifier, onOpenModels: () -> Unit) {
    val state = vm.modelState
    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text("NeuroPocket", style = MaterialTheme.typography.titleMedium)
                    val sub = when (state) {
                        is ModelState.Ready -> state.name
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
                state !is ModelState.Ready -> ModelPlaceholder(state, onOpenModels, vm::reloadModel)
                vm.messages.isEmpty() -> Text(
                    "Модель работает прямо на телефоне — без интернета.\nНапишите что-нибудь.",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> MessageList(vm)
            }
        }

        InputBar(
            enabled = state is ModelState.Ready,
            generating = vm.isGenerating,
            onSend = vm::send,
            onStop = vm::stopGeneration,
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
private fun MessageList(vm: MainViewModel) {
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
            MessageBubble(msg, typing = vm.isGenerating && msg.id == last?.id)
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
private fun MessageBubble(msg: ChatMessage, typing: Boolean) {
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
                    when {
                        answer.isNotEmpty() -> SelectionContainer { Text(answer) }
                        typing -> TypingDots()
                    }
                    msg.stats?.let {
                        Text(
                            it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
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
private fun InputBar(
    enabled: Boolean,
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (enabled) "Сообщение" else "Модель не загружена") },
                enabled = enabled,
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (generating) {
                FilledIconButton(onClick = onStop, modifier = Modifier.padding(bottom = 4.dp)) {
                    // «Стоп» — белый квадрат
                    Box(
                        Modifier.size(14.dp).background(
                            MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp)
                        )
                    )
                }
            } else {
                FilledIconButton(
                    onClick = { onSend(text); text = "" },
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
            }
        }
    }
}
