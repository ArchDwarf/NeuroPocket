package com.neuropocket.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neuropocket.app.data.Settings
import com.neuropocket.app.speech.SpeechController
import com.neuropocket.speech.SPEECH_CATALOG
import com.neuropocket.speech.SpeechKind
import com.neuropocket.speech.SpeechModel
import java.util.Locale

/** Раздел «Речь» во вкладке «Модели» на телефоне. */
@Composable
fun SpeechModelsList(sp: SpeechController, settings: Settings) {
    var customUrl by remember { mutableStateOf("") }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Голосовой ввод (🎤 в чате) и озвучка ответов (🔊). Всё работает без интернета.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        speechSection("Распознавание речи", SpeechKind.ASR, sp, settings)
        speechSection("Голоса для озвучки", SpeechKind.TTS, sp, settings)

        item {
            Text("Озвучка", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp))
        }
        item {
            Column {
                Row {
                    Text("Скорость речи", Modifier.weight(1f))
                    Text(String.format(Locale.US, "%.1f×", settings.ttsSpeed))
                }
                Slider(
                    value = settings.ttsSpeed, valueRange = 0.5f..2f, steps = 14,
                    onValueChange = { v -> sp.saveSpeed(v) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Озвучивать ответы автоматически", Modifier.weight(1f))
                    Switch(checked = settings.autoSpeak, onCheckedChange = { sp.saveAutoSpeak(it) })
                }
            }
        }

        item {
            Text("Другая модель по ссылке", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        }
        item {
            Column {
                OutlinedTextField(
                    value = customUrl, onValueChange = { customUrl = it.trim() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("https://…/модель.tar.bz2") },
                )
                Text(
                    "Подходит любая модель sherpa-onnx (Whisper, Zipformer, Paraformer, SenseVoice, Piper, Kokoro…) " +
                        "со страниц github.com/k2-fsa/sherpa-onnx/releases (asr-models, tts-models).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { sp.install(customUrl); customUrl = "" }, enabled = customUrl.isNotBlank()) {
                    Text("Установить")
                }
            }
        }
    }
}

private fun LazyListScope.speechSection(title: String, kind: SpeechKind, sp: SpeechController, settings: Settings) {
    item(key = "h:$title") {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp))
    }
    val active = if (kind == SpeechKind.ASR) settings.asrModelDir else settings.ttsModelDir
    items(sp.installed.filter { it.kind == kind }, key = { "m:" + it.dir.path }) { m ->
        InstalledSpeechCard(m, m.dir.path == active, onSelect = { sp.select(m) }, onDelete = { sp.delete(m) })
    }
    items(sp.installing.entries.filter { e -> SPEECH_CATALOG.find { it.url == e.key }?.kind?.let { it == kind } ?: (kind == SpeechKind.ASR) }
        .toList(), key = { "i:" + it.key }) { (url, st) ->
        Card {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(st.title, fontWeight = FontWeight.Medium)
                    if (st.progress >= 0) LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${st.stage}${if (st.progress >= 0) " ${(st.progress * 100).toInt()}%" else "…"}",
                        style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { sp.cancelInstall(url) }) { Icon(Icons.Default.Close, "Отменить") }
            }
        }
    }
    items(SPEECH_CATALOG.filter { it.kind == kind && !it.desktopOnly && !sp.isInstalled(it) && it.url !in sp.installing },
        key = { "c:" + it.id }) { item ->
        Card {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.Medium)
                    Text("${item.note} · ≈${item.sizeMb} МБ", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { sp.install(item) }) { Text("Скачать") }
            }
        }
    }
}

@Composable
private fun InstalledSpeechCard(m: SpeechModel, active: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onSelect,
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(m.title, fontWeight = FontWeight.Medium)
                Text(m.type.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
        }
    }
}
