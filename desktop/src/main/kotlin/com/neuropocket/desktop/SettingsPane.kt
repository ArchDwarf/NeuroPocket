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
import androidx.compose.foundation.horizontalScroll
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

        DevicesSection(app, d) { d = it }

        SectionTitle("Память видеокарты")
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
        OutlinedTextField(d.systemPrompt, { d = d.copy(systemPrompt = it) }, label = { Text("Свой системный промпт (режим «⚙️ Свой» в чате)") },
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


/** Выбор устройств: процессор, видеокарта, видеокарта + процессор, несколько видеокарт. */
@Composable
private fun DevicesSection(app: AppState, d: DesktopSettings, onChange: (DesktopSettings) -> Unit) {
    LaunchedEffect(Unit) { app.refreshDevices() }
    val gpus = app.devices.filter { it.isGpu }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Где считать нейросети", Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = { app.refreshDevices() }) { Text("↻ Обновить список") }
    }
    if (app.devices.isEmpty()) Hint("Определяем устройства…")
    app.devices.forEach { dev ->
        val mem = if (dev.totalMb > 0) " · ${formatSize(dev.totalMb shl 20)} (свободно ${formatSize(dev.freeMb shl 20)})" else ""
        Text(
            (if (dev.isGpu) "🎮 " else "🖥 ") + "${dev.name} — ${dev.description}$mem",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (app.devices.isNotEmpty() && gpus.isEmpty()) {
        Hint("Видеокарта с поддержкой Vulkan не найдена — всё будет считать процессор. Обновите драйвер видеокарты.")
    }

    // ---------------- Чат ----------------
    Text("Чат-модели", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("auto" to "Авто (все видеокарты)", "cpu" to "Процессор", "gpu" to "Видеокарта", "hybrid" to "Видеокарта + процессор")
            .forEach { (k, t) -> FilterChip(selected = d.chatDevice == k, onClick = { onChange(d.copy(chatDevice = k)) }, label = { Text(t) }) }
    }
    when (d.chatDevice) {
        "cpu" -> Hint("Всё считает процессор: работает на любом компьютере, но медленнее.")
        "auto" -> Hint("Модель целиком на видеокартах; при нескольких — делится между ними по свободной памяти.")
        "hybrid" -> Hint("Часть слоёв на видеокарте, остальное на процессоре — для моделей больше видеопамяти.")
        else -> Hint("Выберите одну или несколько видеокарт.")
    }
    if ((d.chatDevice == "gpu" || d.chatDevice == "hybrid") && gpus.isNotEmpty()) {
        val chosen = d.chatGpus.filter { n -> gpus.any { it.name == n } }.ifEmpty { gpus.map { it.name } }
        GpuChecklist(gpus.map { it.name to it.description }, chosen) { list ->
            onChange(d.copy(chatGpus = list, chatTensorSplit = emptyList()))
        }
        if (chosen.size > 1) {
            SplitChips(d.chatSplit) { onChange(d.copy(chatSplit = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Доли модели вручную", Modifier.weight(1f))
                Switch(d.chatTensorSplit.size == chosen.size, { on ->
                    onChange(d.copy(chatTensorSplit = if (on) chosen.map { 1f } else emptyList()))
                })
            }
            if (d.chatTensorSplit.size == chosen.size) {
                val total = d.chatTensorSplit.sum().coerceAtLeast(0.01f)
                chosen.forEachIndexed { i, name ->
                    val v = d.chatTensorSplit[i]
                    LabeledSlider(name, "${(v / total * 100).roundToInt()}%", v, 0f..1f) { nv ->
                        onChange(d.copy(chatTensorSplit = d.chatTensorSplit.toMutableList().also { it[i] = (nv * 20).roundToInt() / 20f }))
                    }
                }
            } else {
                Hint("Сейчас доли считаются автоматически по свободной видеопамяти.")
            }
        }
    }
    if (d.chatDevice == "hybrid") {
        LabeledSlider("Слоёв на видеокарте", "${d.gpuLayers.coerceIn(1, 200)}", d.gpuLayers.coerceIn(1, 200).toFloat(), 1f..200f) {
            onChange(d.copy(gpuLayers = it.roundToInt()))
        }
        Hint("У моделей 7–8B ≈ 32 слоя, 14B ≈ 48, 30–32B ≈ 64, 70B ≈ 80. Больше слоёв на видеокарте — быстрее, но нужно больше видеопамяти.")
    }

    // ---------------- Картинки и видео ----------------
    val sdGpus = app.sdDevices.filter { !it.first.equals("CPU", true) }
    Text("Картинки и видео", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("auto" to "Авто", "cpu" to "Процессор", "gpu" to "Видеокарта").forEach { (k, t) ->
            FilterChip(selected = d.sdDevice == k, onClick = { onChange(d.copy(sdDevice = k)) }, label = { Text(t) })
        }
    }
    if (d.sdDevice == "gpu") {
        if (sdGpus.isEmpty()) Hint("Видеокарты для картинок не найдены.")
        else {
            val chosen = d.sdGpus.filter { n -> sdGpus.any { it.first == n } }.ifEmpty { listOf(sdGpus.first().first) }
            GpuChecklist(sdGpus, chosen) { onChange(d.copy(sdGpus = it)) }
            if (chosen.size > 1) {
                Hint("Диффузионная модель разделится между видеокартами (FLUX, Qwen-Image, Wan и др.).")
                SplitChips(d.sdSplit) { onChange(d.copy(sdSplit = it)) }
            }
        }
    }
    if (d.sdDevice != "cpu") {
        val targets = listOf("same" to "там же", "cpu" to "процессор") + sdGpus.map { it.first to it.first }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Текстовые энкодеры (T5, Qwen и др.)", Modifier.weight(1f))
            Dropdown("там же", targets, targets.find { it.first == d.sdTextEncoder }, { it.second }, Modifier.widthIn(min = 180.dp)) {
                onChange(d.copy(sdTextEncoder = it.first))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VAE (кодирование изображения)", Modifier.weight(1f))
            Dropdown("там же", targets, targets.find { it.first == d.sdVae }, { it.second }, Modifier.widthIn(min = 180.dp)) {
                onChange(d.copy(sdVae = it.first))
            }
        }
        Hint("Энкодер на процессоре или второй видеокарте освобождает видеопамять для основной модели.")
    }
    val (backend, split) = Placement.sd(d)
    Hint("Итог для картинок: ${backend.ifEmpty { "автоматически" }}${if (split.isNotEmpty()) " · деление: $split" else ""}")
}

@Composable
private fun GpuChecklist(gpus: List<Pair<String, String>>, chosen: List<String>, onChange: (List<String>) -> Unit) {
    gpus.forEach { (name, desc) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = name in chosen,
                onCheckedChange = { on ->
                    val list = if (on) (chosen + name).distinct() else chosen - name
                    if (list.isNotEmpty()) onChange(gpus.map { it.first }.filter { it in list })
                },
            )
            Text("$name — $desc", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SplitChips(current: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = current != "row", onClick = { onChange("layer") }, label = { Text("По слоям") })
        FilterChip(selected = current == "row", onClick = { onChange("row") }, label = { Text("По строкам (параллельно)") })
    }
    Hint(if (current == "row") "Все видеокарты работают одновременно над каждым слоем — быстрее, если видеокарты одинаковые."
        else "Слои распределяются между видеокартами — надёжно, подходит для разных видеокарт.")
}
