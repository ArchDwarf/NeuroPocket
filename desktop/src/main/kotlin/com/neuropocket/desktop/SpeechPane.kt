package com.neuropocket.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neuropocket.speech.SPEECH_CATALOG
import com.neuropocket.speech.SpeechKind
import kotlin.math.roundToInt

@Composable
fun SpeechPane(app: AppState) {
    val sp = app.speech
    Row(Modifier.fillMaxSize()) {
        // ---------- Работа с речью ----------
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Речь", style = MaterialTheme.typography.titleLarge)
            Hint("Распознавание и синтез речи на компьютере, без интернета.")

            SectionTitle("Распознавание (голос → текст)")
            Hint("Модель: ${sp.asr?.title ?: "не выбрана — установите справа"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 6.dp)) {
                Button(onClick = {
                    sp.toggleDictation { t -> sp.transcript = if (sp.transcript.isBlank()) t else sp.transcript + "\n" + t }
                }, enabled = !sp.transcribing) {
                    Text(if (sp.recording) "⏹ Остановить запись" else "🎤 Записать с микрофона")
                }
                OutlinedButton(onClick = sp::transcribeFile, enabled = !sp.recording && !sp.transcribing) { Text("📄 Расшифровать WAV") }
                if (sp.transcribing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                if (sp.recording) Text("▮".repeat((sp.level * 10).roundToInt().coerceIn(1, 10)), color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = sp.transcript, onValueChange = { sp.transcript = it },
                modifier = Modifier.fillMaxWidth().height(180.dp), label = { Text("Текст") },
            )
            Row {
                TextButton(onClick = { app.chat.input = sp.transcript; app.tab = Tab.CHAT }, enabled = sp.transcript.isNotBlank()) {
                    Text("Отправить в чат")
                }
                TextButton(onClick = { sp.ttsText = sp.transcript }, enabled = sp.transcript.isNotBlank()) { Text("Озвучить этот текст ↓") }
                TextButton(onClick = { sp.transcript = "" }) { Text("Очистить") }
            }

            SectionTitle("Синтез (текст → голос)")
            Hint("Голос: ${sp.tts?.title ?: "не выбран — установите справа"}")
            OutlinedTextField(
                value = sp.ttsText, onValueChange = { sp.ttsText = it },
                modifier = Modifier.fillMaxWidth().height(160.dp).padding(top = 6.dp), label = { Text("Текст для озвучки") },
            )
            LabeledSlider("Скорость", "${fmt(app.settings.ttsSpeed)}×", app.settings.ttsSpeed, 0.5f..2f, 14) {
                app.updateSettings(app.settings.copy(ttsSpeed = (it * 10).roundToInt() / 10f))
            }
            if (sp.numSpeakers() > 1) {
                LabeledSlider("Голос (номер диктора)", "${sp.speaker}", sp.speaker.toFloat(), 0f..(sp.numSpeakers() - 1).toFloat()) {
                    sp.speaker = it.roundToInt()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sp.speakingId == SPEECH_PANE_ID) {
                    Button(onClick = sp::stopSpeaking) { Text("⏹ Стоп") }
                } else {
                    Button(onClick = { sp.speak(SPEECH_PANE_ID, sp.ttsText) }) { Text("🔊 Озвучить") }
                }
                OutlinedButton(onClick = sp::saveSpeech) { Text("💾 Сохранить WAV") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Озвучивать ответы чата автоматически", Modifier.weight(1f))
                Switch(app.settings.autoSpeak, { app.updateSettings(app.settings.copy(autoSpeak = it)) })
            }
        }
        VerticalDivider()
        // ---------- Модели речи ----------
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SpeechModelsSection(app)
        }
    }
}

private const val SPEECH_PANE_ID = -100L

@Composable
fun SpeechModelsSection(app: AppState) {
    val sp = app.speech
    var url by remember { mutableStateOf("") }
    for (kind in SpeechKind.entries) {
        SectionTitle(if (kind == SpeechKind.ASR) "Модели распознавания" else "Голоса")
        val active = if (kind == SpeechKind.ASR) app.settings.asrModelDir else app.settings.ttsModelDir
        sp.installed.filter { it.kind == kind }.forEach { m ->
            Card(
                onClick = { sp.select(m) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = if (m.dir.path == active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else CardDefaults.cardColors(),
            ) {
                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(m.dir.path == active, { sp.select(m) })
                    Column(Modifier.weight(1f)) {
                        Text(m.title, fontWeight = FontWeight.Medium)
                        Hint(m.type.name.lowercase().replace('_', ' '))
                    }
                    TextButton(onClick = { sp.delete(m) }) { Text("Удалить") }
                }
            }
        }
        sp.installing.entries.filter { e -> (SPEECH_CATALOG.find { it.url == e.key }?.kind ?: SpeechKind.ASR) == kind }.forEach { (u, st) ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(st.title)
                        if (st.progress >= 0) LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Hint("${st.stage} ${if (st.progress >= 0) "${(st.progress * 100).roundToInt()}%" else "…"}")
                    }
                    TextButton(onClick = { sp.cancelInstall(u) }) { Text("Отмена") }
                }
            }
        }
        SPEECH_CATALOG.filter { it.kind == kind && !sp.isInstalled(it) && it.url !in sp.installing }.forEach { item ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Medium)
                        Hint("${item.note} · ≈${item.sizeMb} МБ")
                    }
                    FilledTonalButton(onClick = { sp.install(item.url, item.title) }) { Text("Скачать") }
                }
            }
        }
    }
    SectionTitle("Любая модель sherpa-onnx по ссылке")
    OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text("https://github.com/k2-fsa/sherpa-onnx/releases/download/…/модель.tar.bz2") })
    Hint("Сотни моделей распознавания и голосов на десятках языков: github.com/k2-fsa/sherpa-onnx/releases (asr-models, tts-models).")
    Spacer(Modifier.height(6.dp))
    Button(onClick = { sp.install(url); url = "" }, enabled = url.isNotBlank()) { Text("Установить") }
}
