package com.neuropocket.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.neuropocket.app.data.Settings
import java.util.Locale
import kotlin.math.roundToInt

private val CONTEXT_SIZES = listOf(1024, 2048, 4096, 8192, 16384, 32768)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    var draft by remember(vm.settings) { mutableStateOf(vm.settings) }
    var sysInfo by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { sysInfo = runCatching { vm.systemInfo() }.getOrDefault("") }
    val cores = Runtime.getRuntime().availableProcessors()

    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = { Text("Настройки") })
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = draft.systemPrompt,
                onValueChange = { draft = draft.copy(systemPrompt = it) },
                label = { Text("Системный промпт") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
            )
            Spacer(Modifier.height(8.dp))

            Header("Генерация")
            LabeledSlider(
                "Температура (креативность)", fmt(draft.temperature), draft.temperature, 0f..2f, 39,
            ) { draft = draft.copy(temperature = round2(it)) }
            LabeledSlider("Top-P", fmt(draft.topP), draft.topP, 0.1f..1f, 17) {
                draft = draft.copy(topP = round2(it))
            }
            LabeledSlider("Top-K", "${draft.topK}", draft.topK.toFloat(), 0f..100f, 19) {
                draft = draft.copy(topK = it.roundToInt())
            }
            LabeledSlider("Штраф за повторы", fmt(draft.repeatPenalty), draft.repeatPenalty, 1f..1.5f, 9) {
                draft = draft.copy(repeatPenalty = round2(it))
            }
            LabeledSlider("Макс. длина ответа (токенов)", "${draft.maxTokens}", draft.maxTokens.toFloat(), 128f..4096f, 30) {
                draft = draft.copy(maxTokens = (it / 128).roundToInt() * 128)
            }

            Header("Производительность (перезагрузит модель)")
            val ctxIndex = CONTEXT_SIZES.indexOf(draft.contextSize).coerceAtLeast(0)
            LabeledSlider(
                "Размер контекста (память чата)", "${draft.contextSize} ток.",
                ctxIndex.toFloat(), 0f..(CONTEXT_SIZES.size - 1).toFloat(), CONTEXT_SIZES.size - 2,
            ) { draft = draft.copy(contextSize = CONTEXT_SIZES[it.roundToInt()]) }
            Text(
                "Больший контекст помнит длинные переписки, но требует больше ОЗУ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledSlider(
                "Потоки процессора", "${draft.threads} из $cores",
                draft.threads.toFloat(), 1f..cores.toFloat(), (cores - 2).coerceAtLeast(0),
            ) { draft = draft.copy(threads = it.roundToInt()) }

            Header("Фото и видео (модели со зрением)")
            LabeledSlider(
                "Размер фото для модели", "${draft.visionMaxSide} px",
                draft.visionMaxSide.toFloat(), 384f..1280f, 6,
            ) { draft = draft.copy(visionMaxSide = ((it / 128).roundToInt() * 128).coerceIn(384, 1280)) }
            LabeledSlider(
                "Кадров из видео", "${draft.videoFrames}",
                draft.videoFrames.toFloat(), 2f..16f, 13,
            ) { draft = draft.copy(videoFrames = it.roundToInt()) }
            Text(
                "Меньше размер и кадров — быстрее ответ. Каждый кадр обрабатывается как отдельное фото.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Header("Генерация картинок")
            Text("Сжатие весов при загрузке модели", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "как в файле", "f16" to "F16", "q8_0" to "Q8", "q4_0" to "Q4").forEach { (k, t) ->
                    FilterChip(
                        selected = draft.imageWeightType == k,
                        onClick = { draft = draft.copy(imageWeightType = k) },
                        label = { Text(t) },
                    )
                }
            }
            Text(
                "Q8/Q4 уменьшают расход памяти большими моделями (немного хуже качество). " +
                    "Применяется при следующей загрузке модели.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Header("Hugging Face")
            OutlinedTextField(
                value = draft.hfToken,
                onValueChange = { draft = draft.copy(hfToken = it.trim()) },
                label = { Text("Токен доступа (необязательно)") },
                supportingText = { Text("Нужен только для закрытых моделей (Llama, Gemma и т.п. в официальных репозиториях)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.updateSettings(
                            draft.copy(
                                activeModelPath = vm.settings.activeModelPath,
                                imageModelPath = vm.settings.imageModelPath,
                            )
                        )
                    },
                    enabled = draft != vm.settings,
                ) { Text("Сохранить") }
                OutlinedButton(onClick = {
                    draft = Settings(
                        hfToken = draft.hfToken,
                        activeModelPath = vm.settings.activeModelPath,
                        imageModelPath = vm.settings.imageModelPath,
                    )
                }) { Text("По умолчанию") }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "Устройство: ОЗУ ${formatSize(vm.totalRamBytes)}, ядер $cores\n" +
                    "Папка моделей: ${vm.modelsDirPath}\n$sysInfo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)
private fun round2(v: Float) = (v * 100).roundToInt() / 100f

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = current.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}
