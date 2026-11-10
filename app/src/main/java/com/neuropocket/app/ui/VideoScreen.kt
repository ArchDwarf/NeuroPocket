package com.neuropocket.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.neuropocket.app.data.Media
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val v = vm.video
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    // Экран не гаснет, пока создаётся видео (иначе система может остановить работу)
    LaunchedEffect(v.generating) { view.keepScreenOn = v.generating }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { Media.loadBitmap(context, uri, 1280) }
                .onSuccess { v.source = it }
                .onFailure { android.widget.Toast.makeText(context, "Не удалось открыть фото", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Видео по описанию") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- Модель ----------
            Text("Модель", style = MaterialTheme.typography.titleMedium)
            @Suppress("UNUSED_VARIABLE") val rev = v.revision
            for (p in v.presets) {
                val selected = p.id == v.presetId
                val installed = v.isInstalled(p)
                val progress = v.downloadProgress[p.id]
                Card(
                    Modifier.fillMaxWidth().clickable { v.choose(p) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (selected) "● " else "○ ") + p.title,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                            )
                            if (installed) Text("✓ скачана", color = MaterialTheme.colorScheme.primary)
                        }
                        Text(p.note, style = MaterialTheme.typography.bodySmall)
                        when {
                            progress != null -> {
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Скачивание: ${(progress * 100).roundToInt()}%", Modifier.weight(1f))
                                    TextButton(onClick = { v.cancelDownload(p) }) { Text("Отменить") }
                                }
                            }
                            v.resolving == p.id -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                                Text("Ищу файлы на Hugging Face…")
                            }
                            installed -> Row {
                                Text(
                                    String.format(Locale.US, "%.1f ГБ на телефоне", v.installedSizeGb(p)),
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                                )
                                TextButton(onClick = { v.delete(p) }, enabled = !v.generating) { Text("Удалить") }
                            }
                            else -> OutlinedButton(onClick = { v.download(p) }) { Text("Скачать") }
                        }
                    }
                }
            }

            // ---------- Описание ----------
            OutlinedTextField(
                value = v.prompt, onValueChange = { v.prompt = it },
                label = { Text("Что происходит в видео") },
                placeholder = { Text("Например: a cat walking on a sunny beach, waves, cinematic") },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
            )
            Text(
                "Видеомодели лучше понимают английский. Описывайте сцену и движение: кто, что делает, как движется камера.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (v.preset.canAnimatePhoto) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val src = v.source
                    if (src != null) {
                        Image(
                            src.asImageBitmap(), "Первый кадр",
                            Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Видео начнётся с этого фото")
                            TextButton(onClick = { v.source = null }) { Text("Убрать фото") }
                        }
                    } else {
                        OutlinedButton(onClick = {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("🖼 Оживить фото (необязательно)") }
                    }
                }
            }

            // ---------- Параметры ----------
            Text("Размер кадра", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoSize.entries.forEach { s ->
                    FilterChip(selected = v.size == s, onClick = { v.size = s }, label = { Text(s.title) })
                }
            }
            val fps = v.preset.fps
            Text(
                String.format(Locale.US, "Длина: %d кадров (≈%.1f с)", v.frames, v.frames.toFloat() / fps),
                style = MaterialTheme.typography.titleSmall,
            )
            // Wan: число кадров вида 4n+1
            Slider(
                value = ((v.frames - 1) / 4).toFloat(), onValueChange = { v.frames = it.roundToInt() * 4 + 1 },
                valueRange = 2f..20f, steps = 17,
            )
            Text("Шаги: ${v.steps} (меньше — быстрее, больше — лучше)", style = MaterialTheme.typography.titleSmall)
            Slider(value = v.steps.toFloat(), onValueChange = { v.steps = it.roundToInt() }, valueRange = 6f..30f, steps = 23)
            OutlinedTextField(
                value = v.negative, onValueChange = { v.negative = it },
                label = { Text("Чего не должно быть") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
            )

            // ---------- Запуск ----------
            if (v.generating) {
                val total = v.totalSteps.coerceAtLeast(1)
                Text(
                    when {
                        v.phase.isNotEmpty() && v.step == 0 -> v.phase
                        else -> buildString {
                            append("Шаг ${v.step} из $total")
                            if (v.secondsPerStep > 0) {
                                val left = ((total - v.step) * v.secondsPerStep).roundToInt()
                                append(" · осталось ≈ ${left / 60} мин ${left % 60} с")
                            }
                        }
                    },
                )
                LinearProgressIndicator(progress = { v.step.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Не закрывайте приложение: видео на телефоне считается долго (обычно 5–30 минут).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { v.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
            } else {
                Button(
                    onClick = { v.generate() },
                    enabled = v.isInstalled(v.preset) && v.prompt.isNotBlank() && !v.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🎬 Создать видео") }
                if (!v.isInstalled(v.preset)) {
                    Text("Сначала скачайте выбранную модель (кнопка «Скачать» выше).", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---------- Результат ----------
            v.clip?.let { c -> ClipPlayer(c, onSave = { v.saveToGallery(c) }, onShare = {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", c.file)
                val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Отправить видео"))
            }) }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ClipPlayer(c: VideoClip, onSave: () -> Unit, onShare: () -> Unit) {
    var playing by remember(c) { mutableStateOf(true) }
    var frame by remember(c) { mutableIntStateOf(0) }
    LaunchedEffect(c, playing) {
        while (playing && c.frames.isNotEmpty()) {
            delay(1000L / c.fps.coerceAtLeast(1))
            frame = (frame + 1) % c.frames.size
        }
    }
    Text("Готово за ${c.seconds.roundToInt() / 60} мин ${c.seconds.roundToInt() % 60} с", style = MaterialTheme.typography.titleMedium)
    Box(
        Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(12.dp)).clickable { playing = !playing },
        contentAlignment = Alignment.Center,
    ) {
        if (c.frames.isNotEmpty()) {
            Image(c.frames[frame].asImageBitmap(), "Видео", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        }
        if (!playing) Text("▶", color = Color.White, style = MaterialTheme.typography.displayMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) "⏸" else "▶") }
        Button(onClick = onSave) { Text("💾 В галерею") }
        OutlinedButton(onClick = onShare) { Text("Отправить") }
    }
}
