package com.neuropocket.app.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neuropocket.sd.SdMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(vm: MainViewModel, modifier: Modifier, onOpenModels: () -> Unit) {
    val st = vm.studio
    LaunchedEffect(Unit) { st.ensureLoaded() }

    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text("Картинки", style = MaterialTheme.typography.titleMedium)
                    val sub = when (val s = st.state) {
                        is SdState.Ready -> "${s.name} · ${s.arch}"
                        is SdState.Loading -> "Загрузка ${s.name}…"
                        is SdState.Error -> "Ошибка загрузки"
                        SdState.None -> "Модель не выбрана"
                    }
                    Text(
                        sub, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        when (val s = st.state) {
            is SdState.Ready -> Studio(vm)
            is SdState.Loading -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Загружаем модель в память…")
            }
            is SdState.Error -> Centered {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = st::reload) { Text("Повторить") }
                TextButton(onClick = onOpenModels) { Text("Выбрать другую модель") }
            }
            SdState.None -> Centered {
                Text("Генерация и редактирование изображений прямо на телефоне.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Скачайте модель для картинок, например SDXS (рисует за секунды) или Stable Diffusion 1.5.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenModels) { Text("Выбрать модель") }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Studio(vm: MainViewModel) {
    val st = vm.studio
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) st.setSource(uri)
    }
    var videoForFrame by remember { mutableStateOf<Uri?>(null) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) videoForFrame = uri
    }
    var advanced by remember { mutableStateOf(false) }
    var drawMask by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // ---------- Результат ----------
        ResultArea(vm)

        if (st.results.size > 1) {
            Row(
                Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                st.results.forEach { r ->
                    Image(
                        r.bitmap.asImageBitmap(), null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                            .then(
                                if (r === st.current) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                else Modifier
                            )
                            .clickable { st.current = r },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---------- Исходник ----------
        Text("Исходное фото", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        val src = st.source
        if (src == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("🖼 Фото") }
                OutlinedButton(onClick = {
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }) { Text("🎞 Кадр из видео") }
            }
            Text(
                "Необязательно: добавьте фото, чтобы изменить его по описанию.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MaskEditor(src, st, drawMask && st.mode == SdMode.REDRAW)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (st.mode == SdMode.REDRAW) {
                    FilterChip(
                        selected = drawMask, onClick = { drawMask = !drawMask },
                        label = { Text(if (drawMask) "Рисую маску" else "Маска") },
                    )
                    if (st.maskStrokes.isNotEmpty()) {
                        TextButton(onClick = { st.maskStrokes.removeAt(st.maskStrokes.lastIndex) }) { Text("Отменить") }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { drawMask = false; st.clearSource() }) { Text("Убрать фото") }
            }
            if (st.mode == SdMode.REDRAW && drawMask) {
                LabeledSlider("Размер кисти", "${(st.brushSize * 100).roundToInt()}%", st.brushSize, 0.02f..0.2f) {
                    st.brushSize = it
                }
                Text(
                    "Закрасьте пальцем область, которую нужно изменить. Без маски перерисовывается всё фото.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---------- Режим ----------
        val modes = listOf(SdMode.TEXT to "По тексту", SdMode.REDRAW to "Перерисовать", SdMode.INSTRUCT to "Инструкция")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (m, title) ->
                SegmentedButton(
                    selected = st.mode == m,
                    onClick = { st.mode = m },
                    enabled = m == SdMode.TEXT || src != null,
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(title, maxLines = 1) }
            }
        }
        val hint = when (st.mode) {
            SdMode.TEXT -> "Новая картинка по описанию."
            SdMode.REDRAW -> "Фото перерисовывается по описанию; с маской — только закрашенная часть."
            SdMode.INSTRUCT -> if (st.isEditModel) "Напишите, что изменить: «make it winter», «turn him into a robot»."
            else "⚠ Нужна edit-модель (например Instruct-Pix2Pix). Текущая модель лучше работает в режиме «Перерисовать»."
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = st.prompt,
            onValueChange = { st.prompt = it },
            label = { Text(if (st.mode == SdMode.INSTRUCT) "Что изменить (на английском)" else "Описание (на английском)") },
            placeholder = { Text("a cozy cabin in snowy mountains, sunset, detailed") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5,
        )

        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Скрыть настройки ▲" else "Настройки ▼")
        }
        if (advanced) AdvancedSettings(vm)

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = st::generate,
            enabled = !st.generating,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                when (st.mode) {
                    SdMode.TEXT -> "Сгенерировать"
                    SdMode.REDRAW -> "Перерисовать"
                    SdMode.INSTRUCT -> "Изменить"
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Видео целиком нейросеть на телефоне изменить не может — это требует мощных видеокарт. " +
                "Можно взять кадр из видео и отредактировать его, а само видео отправить в чат модели " +
                "со зрением (👁), чтобы она описала его или ответила на вопросы.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    videoForFrame?.let { uri ->
        FramePicker(uri, onDismiss = { videoForFrame = null }) { bmp ->
            videoForFrame = null
            st.useAsSource(bmp)
        }
    }
}

@Composable
private fun ResultArea(vm: MainViewModel) {
    val st = vm.studio
    val cur = st.current
    Card(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 220.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (cur != null) {
                Image(
                    cur.bitmap.asImageBitmap(), "Результат",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(cur.bitmap.width.toFloat() / cur.bitmap.height),
                )
            } else if (!st.generating) {
                Text("Здесь появится результат", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (st.generating) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .then(Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val total = st.totalSteps.coerceAtLeast(1)
                    val label = if (st.step == 0) "Подготовка…" else "Шаг ${st.step} из $total"
                    Card {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            if (st.step == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { st.step.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                            if (st.step > 0 && st.secondsPerStep > 0) {
                                val left = ((total - st.step) * st.secondsPerStep).roundToInt()
                                Text(
                                    String.format(Locale.US, "%.1f с/шаг · осталось ≈ %d с", st.secondsPerStep, left),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = st::cancel) { Text("Остановить") }
                        }
                    }
                }
            }
        }
        if (cur != null && !st.generating) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    String.format(Locale.US, "seed %d · %.1f с", cur.seed, cur.seconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = { st.save(cur.bitmap) }) { Text("Сохранить") }
                TextButton(onClick = { st.useAsSource(cur.bitmap) }) { Text("Править дальше") }
            }
        }
    }
}

/** Фото с возможностью закрасить пальцем область для перерисовки. */
@Composable
private fun MaskEditor(src: Bitmap, st: ImageStudio, drawing: Boolean) {
    val current = remember { mutableStateListOf<Pair<Float, Float>>() }
    val maskColor = Color(0x88FF3B30)
    Box(
        Modifier.fillMaxWidth().aspectRatio(src.width.toFloat() / src.height).clip(RoundedCornerShape(12.dp))
    ) {
        Image(src.asImageBitmap(), "Исходное фото", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        val gestures = if (drawing) Modifier
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    st.maskStrokes += MaskStroke(listOf(o.x / size.width to o.y / size.height), st.brushSize)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> current.clear(); current += o.x / size.width to o.y / size.height },
                    onDrag = { change, _ ->
                        current += change.position.x / size.width to change.position.y / size.height
                        change.consume()
                    },
                    onDragEnd = {
                        if (current.isNotEmpty()) st.maskStrokes += MaskStroke(current.toList(), st.brushSize)
                        current.clear()
                    },
                    onDragCancel = { current.clear() },
                )
            } else Modifier
        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val dim = maxOf(size.width, size.height)
            fun drawStroke(points: List<Pair<Float, Float>>, radius: Float) {
                val px = points.map { Offset(it.first * size.width, it.second * size.height) }
                if (px.size == 1) {
                    drawCircle(maskColor, radius * dim, px[0])
                } else {
                    for (i in 1 until px.size) {
                        drawLine(maskColor, px[i - 1], px[i], strokeWidth = radius * 2 * dim, cap = StrokeCap.Round)
                    }
                }
            }
            st.maskStrokes.forEach { drawStroke(it.points, it.radius) }
            if (current.isNotEmpty()) drawStroke(current, st.brushSize)
            if (drawing) drawRect(Color(0xFFFF3B30), style = Stroke(width = 4f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSettings(vm: MainViewModel) {
    val st = vm.studio
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = st.negative,
            onValueChange = { st.negative = it },
            label = { Text("Чего избегать (negative prompt)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
        LabeledSlider("Шаги", "${st.steps}", st.steps.toFloat(), 1f..50f) { st.steps = it.roundToInt() }
        LabeledSlider("Следование описанию (CFG)", fmt1(st.cfg), st.cfg, 1f..15f) { st.cfg = (it * 2).roundToInt() / 2f }
        if (st.mode == SdMode.REDRAW) {
            LabeledSlider("Сила изменений", "${(st.strength * 100).roundToInt()}%", st.strength, 0.1f..1f) {
                st.strength = (it * 20).roundToInt() / 20f
            }
        }
        if (st.mode == SdMode.INSTRUCT) {
            LabeledSlider("Близость к исходнику", fmt1(st.imageCfg), st.imageCfg, 1f..3f) {
                st.imageCfg = (it * 10).roundToInt() / 10f
            }
        }
        Text("Размер (по большей стороне)", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(256, 384, 512, 768, 1024).forEach { s ->
                FilterChip(selected = st.side == s, onClick = { st.side = s }, label = { Text("$s") })
            }
        }
        if (st.mode == SdMode.TEXT) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Aspect.entries.forEach { a ->
                    FilterChip(selected = st.aspect == a, onClick = { st.aspect = a }, label = { Text(a.title) })
                }
            }
        }
        Text("Сэмплер", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("" to "авто", "euler_a" to "Euler a", "euler" to "Euler", "dpm++2m" to "DPM++ 2M", "lcm" to "LCM").forEach { (k, t) ->
                FilterChip(selected = st.sampler == k, onClick = { st.sampler = k }, label = { Text(t) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Фиксированный seed", Modifier.weight(1f))
            Switch(
                checked = st.fixedSeed != null,
                onCheckedChange = { on -> st.fixedSeed = if (on) (st.current?.seed ?: 42L) else null },
            )
        }
        st.fixedSeed?.let { seed ->
            OutlinedTextField(
                value = seed.toString(),
                onValueChange = { v -> v.toLongOrNull()?.let { st.fixedSeed = it } },
                label = { Text("Seed") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = current.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

/** Выбор кадра из видео для редактирования. */
@Composable
private fun FramePicker(uri: Uri, onDismiss: () -> Unit, onPick: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val retriever = remember { MediaMetadataRetriever() }
    // MediaMetadataRetriever не потокобезопасен
    val lock = remember { Mutex() }
    var durationMs by remember { mutableStateOf(0L) }
    var position by remember { mutableStateOf(0f) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                lock.withLock { retriever.setDataSource(context, uri) }
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                error = "Не удалось открыть видео"
            }
        }
    }
    LaunchedEffect(position, durationMs) {
        if (durationMs <= 0) return@LaunchedEffect
        frame = withContext(Dispatchers.IO) {
            runCatching {
                lock.withLock {
                    retriever.getFrameAtTime((position * durationMs * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                }
            }.getOrNull()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { scope.launch(Dispatchers.IO) { lock.withLock { retriever.release() } } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите кадр") },
        text = {
            Column {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                frame?.let { f ->
                    Image(
                        f.asImageBitmap(), null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(f.width.toFloat() / f.height),
                    )
                } ?: Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    if (error == null) CircularProgressIndicator()
                }
                Slider(value = position, onValueChange = { position = it })
                Text(
                    String.format(Locale.US, "%.1f с из %.1f с", position * durationMs / 1000f, durationMs / 1000f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { frame?.let { f -> scope.launch { onPick(f.copy(Bitmap.Config.ARGB_8888, false)) } } },
                enabled = frame != null,
            ) { Text("Использовать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
