package com.neuropocket.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val CONTEXT_SIZES = listOf(2048, 4096, 8192, 16384, 32768, 65536, 131072)

@Composable
fun SettingsPane(app: AppState) {
    var d by remember(app.settings) { mutableStateOf(app.settings) }
    var sysInfo by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { sysInfo = app.chat.systemInfo() }
    val cores = Runtime.getRuntime().availableProcessors()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 900.dp)) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge)

        SectionTitle("Видеокарта")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Использовать видеокарту (Vulkan: NVIDIA, AMD, Intel)")
                Hint("Ускоряет всё в 5–50 раз. Без видеокарты работает процессор.")
            }
            Switch(d.useGpu, { d = d.copy(useGpu = it) })
        }
        LabeledSlider(
            "Слоёв чат-модели на видеокарте", if (d.gpuLayers >= 999) "все" else "${d.gpuLayers}",
            d.gpuLayers.coerceAtMost(100).toFloat(), 0f..100f,
        ) { d = d.copy(gpuLayers = if (it >= 99.5f) 999 else it.roundToInt()) }
        Hint("Если модель не помещается в видеопамять — уменьшите: часть слоёв будет считать процессор.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Картинки и видео: держать веса в оперативной памяти")
                Hint("Позволяет запускать большие модели (FLUX, Qwen-Image, Wan 14B) на видеокартах с 6–8 ГБ, но медленнее.")
            }
            Switch(d.sdOffloadToCpu, { d = d.copy(sdOffloadToCpu = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Обрабатывать изображение по частям (VAE tiling)")
                Hint("Экономит видеопамять на больших размерах и видео.")
            }
            Switch(d.sdVaeTiling, { d = d.copy(sdVaeTiling = it) })
        }
        Text("Сжатие весов картинок/видео при загрузке", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("" to "как в файле", "f16" to "F16", "q8_0" to "Q8", "q4_K" to "Q4_K").forEach { (k, t) ->
                FilterChip(selected = d.sdWeightType == k, onClick = { d = d.copy(sdWeightType = k) }, label = { Text(t) })
            }
        }
        OutlinedTextField(
            d.sdMaxVram, { d = d.copy(sdMaxVram = it.trim()) }, singleLine = true,
            label = { Text("Лимит видеопамяти для картинок/видео, ГБ (пусто — без лимита)") },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        SectionTitle("Чат")
        OutlinedTextField(d.systemPrompt, { d = d.copy(systemPrompt = it) }, label = { Text("Системный промпт") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
        LabeledSlider("Температура", fmt(d.temperature, 2), d.temperature, 0f..2f) { d = d.copy(temperature = (it * 20).roundToInt() / 20f) }
        LabeledSlider("Top-P", fmt(d.topP, 2), d.topP, 0.1f..1f) { d = d.copy(topP = (it * 20).roundToInt() / 20f) }
        LabeledSlider("Top-K", "${d.topK}", d.topK.toFloat(), 0f..200f) { d = d.copy(topK = it.roundToInt()) }
        LabeledSlider("Штраф за повторы", fmt(d.repeatPenalty, 2), d.repeatPenalty, 1f..1.5f) { d = d.copy(repeatPenalty = (it * 50).roundToInt() / 50f) }
        LabeledSlider("Макс. длина ответа", "${d.maxTokens}", d.maxTokens.toFloat(), 256f..16384f) { d = d.copy(maxTokens = (it / 256).roundToInt() * 256) }
        val ci = CONTEXT_SIZES.indexOf(d.contextSize).coerceAtLeast(0)
        LabeledSlider("Размер контекста", "${d.contextSize} ток.", ci.toFloat(), 0f..(CONTEXT_SIZES.size - 1).toFloat(), CONTEXT_SIZES.size - 2) {
            d = d.copy(contextSize = CONTEXT_SIZES[it.roundToInt()])
        }
        LabeledSlider("Потоки процессора", "${d.threads} из $cores", d.threads.toFloat(), 1f..cores.toFloat()) { d = d.copy(threads = it.roundToInt()) }
        LabeledSlider("Размер фото для модели", "${d.visionMaxSide} px", d.visionMaxSide.toFloat(), 384f..2048f) {
            d = d.copy(visionMaxSide = ((it / 128).roundToInt() * 128).coerceIn(384, 2048))
        }
        LabeledSlider("Кадров из видео", "${d.videoFrames}", d.videoFrames.toFloat(), 2f..32f) { d = d.copy(videoFrames = it.roundToInt()) }

        SectionTitle("Hugging Face")
        OutlinedTextField(
            d.hfToken, { d = d.copy(hfToken = it.trim()) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("Токен доступа (для закрытых моделей: Llama, FLUX.1-dev и т.п.)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("Создайте токен на huggingface.co/settings/tokens и примите лицензию модели на её странице.")

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                app.updateSettings(
                    d.copy(
                        chatModel = app.settings.chatModel, imageProfile = app.settings.imageProfile,
                        videoProfile = app.settings.videoProfile, asrModelDir = app.settings.asrModelDir,
                        ttsModelDir = app.settings.ttsModelDir,
                    )
                )
                app.toast("Настройки сохранены")
            }, enabled = d != app.settings) { Text("Сохранить") }
            OutlinedButton(onClick = {
                d = DesktopSettings(
                    chatModel = app.settings.chatModel, imageProfile = app.settings.imageProfile,
                    videoProfile = app.settings.videoProfile, asrModelDir = app.settings.asrModelDir,
                    ttsModelDir = app.settings.ttsModelDir, hfToken = app.settings.hfToken,
                )
            }) { Text("По умолчанию") }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        SectionTitle("О системе")
        SelectionContainer {
            Text(
                "Оперативная память: ${formatSize(app.totalRamBytes)} · ядер: $cores\n" +
                    "Нативные библиотеки: ${NativeDir.dir.absolutePath}\n" +
                    "Картинки/видео: ${app.images.backendName().ifEmpty { "ещё не загружено" }}\n$sysInfo",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
