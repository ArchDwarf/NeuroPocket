package com.neuropocket.desktop

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neuropocket.sd.SdMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ImagePane(app: AppState, c: ImageController) {
    LaunchedEffect(c) { c.ensureLoaded() }
    Column(Modifier.fillMaxSize()) {
        Header(app, c)
        when (val s = c.state) {
            is SdLoadState.Loading -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Загружаем модель «${s.name}»… Большие модели загружаются до нескольких минут.")
            }
            is SdLoadState.Error -> Centered {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = c::reload) { Text("Повторить") }
                    OutlinedButton(onClick = { app.tab = Tab.MODELS }) { Text("Модели") }
                }
            }
            SdLoadState.None -> Centered {
                Text(
                    if (c.video) "Генерация видео нейросетью Wan прямо на компьютере." else "Генерация и редактирование изображений на компьютере.",
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Hint(
                    if (c.profiles.isEmpty()) "Скачайте набор во вкладке «Модели» → «Картинки и видео» — например " +
                        (if (c.video) "Wan 2.1 1.3B." else "Z-Image Turbo или FLUX.2 klein.")
                    else "Выберите модель в списке вверху.",
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { app.tab = Tab.MODELS }) { Text("Перейти к моделям") }
            }
            is SdLoadState.Ready -> Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(440.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Controls(app, c)
                }
                VerticalDivider()
                Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    if (c.video) VideoResultView(c) else ImageResultView(app, c)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Header(app: AppState, c: ImageController) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (c.video) "Видео" else "Картинки", style = MaterialTheme.typography.titleLarge)
            val s = c.state
            Hint(
                when (s) {
                    is SdLoadState.Ready -> "${s.name} · ${s.arch} · ${c.backendName().ifEmpty { "?" }}" +
                        (if (c.video && !s.canVideo) " · ⚠ эта модель не умеет видео" else "") +
                        (if (!c.video && !s.canImage) " · ⚠ это видеомодель — откройте вкладку «Видео»" else "")
                    is SdLoadState.Loading -> "Загрузка…"
                    is SdLoadState.Error -> "Ошибка загрузки"
                    SdLoadState.None -> "Модель не выбрана"
                },
            )
        }
        Dropdown(
            "Выбрать модель", c.profiles, c.profile, { it.name }, Modifier.widthIn(min = 260.dp, max = 400.dp),
        ) { c.select(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(app: AppState, c: ImageController) {
    var framePick by remember { mutableStateOf<Pair<File, Double>?>(null) }
    var drawMask by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    // ---------- Режим ----------
    if (!c.video) {
        val modes = listOf(SdMode.TEXT to "По тексту", SdMode.REDRAW to "Перерисовать", SdMode.INSTRUCT to "Инструкция")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (m, t) ->
                SegmentedButton(
                    selected = c.mode == m, onClick = { c.mode = m },
                    enabled = m == SdMode.TEXT || c.source != null,
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(t, maxLines = 1) }
            }
        }
        Hint(
            when (c.mode) {
                SdMode.TEXT -> "Новая картинка по описанию."
                SdMode.REDRAW -> "Изображение перерисовывается по описанию; с маской — только закрашенная область."
                SdMode.INSTRUCT -> if (c.canEdit) "Напишите, что изменить: «make it winter», «add a hat»."
                else "⚠ Нужна edit-модель (FLUX.2 klein, Qwen-Image-Edit, Pix2Pix). Для этой модели выберите «Перерисовать»."
            },
            Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
    }

    // ---------- Исходник ----------
    SectionTitle(if (c.video) "Первый кадр (необязательно)" else "Исходное изображение")
    val src = c.sourceBitmap
    if (src == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = c::chooseSource) { Text("🖼 Изображение") }
            OutlinedButton(onClick = { c.chooseVideoFrame { f, d -> framePick = f to d } }) { Text("🎞 Кадр из видео") }
            if (!c.video && c.current != null) {
                OutlinedButton(onClick = { c.setSource(c.current!!.image) }) { Text("Результат") }
            }
        }
        Hint(if (c.video) "С картинкой модель «оживит» её (Wan 2.2 5B / A14B)." else "Добавьте, чтобы изменить существующее изображение.")
    } else {
        MaskEditor(src, c, drawMask && c.mode == SdMode.REDRAW && !c.video)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!c.video && c.mode == SdMode.REDRAW) {
                FilterChip(selected = drawMask, onClick = { drawMask = !drawMask }, label = { Text(if (drawMask) "Рисую маску" else "Маска") })
                if (c.strokes.isNotEmpty()) TextButton(onClick = { c.strokes.removeAt(c.strokes.lastIndex) }) { Text("Отменить") }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { drawMask = false; c.clearSource() }) { Text("Убрать") }
        }
        if (drawMask && c.mode == SdMode.REDRAW) {
            LabeledSlider("Кисть", "${(c.brush * 100).roundToInt()}%", c.brush, 0.01f..0.2f) { c.brush = it }
        }
    }

    // ---------- Описание ----------
    SectionTitle("Описание")
    OutlinedTextField(
        value = c.prompt, onValueChange = { c.prompt = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
        placeholder = { Text(if (c.video) "a cat walking on the beach at sunset, cinematic" else "a cozy cabin in snowy mountains, sunset, detailed") },
    )
    Hint("Лучше всего модели понимают английский. Z-Image, Qwen-Image и Wan понимают и другие языки.")
    OutlinedTextField(
        value = c.negative, onValueChange = { c.negative = it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        maxLines = 3, label = { Text("Чего избегать") },
    )

    // ---------- Размер ----------
    SectionTitle("Размер")
    val sizes = if (c.video) listOf(480 to 832, 832 to 480, 480 to 480, 704 to 1280, 1280 to 704)
    else listOf(512 to 512, 768 to 768, 1024 to 1024, 832 to 1216, 1216 to 832, 1344 to 768)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sizes.forEach { (w, h) ->
            FilterChip(selected = c.width == w && c.height == h, onClick = { c.width = w; c.height = h }, label = { Text("${w}×$h") })
        }
    }
    if (c.video) {
        LabeledSlider("Кадров", "${c.frames} (${fmt(c.frames.toFloat() / c.fps)} с)", c.frames.toFloat(), 5f..121f) {
            // Wan: число кадров вида 4n+1
            c.frames = ((it.roundToInt() - 1) / 4) * 4 + 1
        }
        LabeledSlider("Кадров в секунду", "${c.fps}", c.fps.toFloat(), 8f..30f) { c.fps = it.roundToInt() }
    }

    // ---------- Параметры ----------
    LabeledSlider("Шаги", "${c.steps}", c.steps.toFloat(), 1f..60f) { c.steps = it.roundToInt() }
    LabeledSlider("Следование описанию (CFG)", fmt(c.cfg), c.cfg, 1f..15f) { c.cfg = (it * 2).roundToInt() / 2f }
    if (!c.video && c.mode == SdMode.REDRAW) {
        LabeledSlider("Сила изменений", "${(c.strength * 100).roundToInt()}%", c.strength, 0.05f..1f) { c.strength = (it * 20).roundToInt() / 20f }
    }
    if (!c.video && c.mode == SdMode.INSTRUCT) {
        LabeledSlider("Близость к исходнику", fmt(c.imageCfg), c.imageCfg, 1f..3f) { c.imageCfg = (it * 10).roundToInt() / 10f }
    }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Дополнительно ▲" else "Дополнительно ▼") }
    if (advanced) {
        Text("Сэмплер", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("" to "авто", "euler" to "Euler", "euler_a" to "Euler a", "dpm++2m" to "DPM++ 2M", "lcm" to "LCM", "res_multistep" to "RES").forEach { (k, t) ->
                FilterChip(selected = c.sampler == k, onClick = { c.sampler = k }, label = { Text(t) })
            }
        }
        if (c.video) {
            LabeledSlider("Flow shift (0 — авто)", fmt(c.flowShift), c.flowShift, 0f..12f) { c.flowShift = (it * 2).roundToInt() / 2f }
            LabeledSlider("Шаги второй модели (A14B)", "${c.highNoiseSteps}", c.highNoiseSteps.toFloat(), 0f..30f) { c.highNoiseSteps = it.roundToInt() }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Фиксированный seed", Modifier.weight(1f))
            Switch(checked = c.fixedSeed != null, onCheckedChange = { on -> c.fixedSeed = if (on) (c.current?.seed ?: 42L) else null })
        }
        c.fixedSeed?.let { seed ->
            OutlinedTextField(seed.toString(), { v -> v.toLongOrNull()?.let { c.fixedSeed = it } }, singleLine = true,
                label = { Text("Seed") }, modifier = Modifier.fillMaxWidth())
        }
    }

    Spacer(Modifier.height(12.dp))
    if (c.running) {
        val total = c.totalSteps.coerceAtLeast(1)
        Text(if (c.step == 0) "${c.phase}…" else "${c.phase}: шаг ${c.step} из $total", fontWeight = FontWeight.Medium)
        if (c.step == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { c.step.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        if (c.secPerStep > 0 && c.step > 0) Hint("${fmt(c.secPerStep)} с/шаг · осталось ≈ ${((total - c.step) * c.secPerStep).roundToInt()} с")
        OutlinedButton(onClick = c::cancel, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Остановить") }
    } else {
        Button(onClick = c::generate, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(
                when {
                    c.video -> "Создать видео"
                    c.mode == SdMode.TEXT -> "Сгенерировать"
                    c.mode == SdMode.REDRAW -> "Перерисовать"
                    else -> "Изменить"
                },
            )
        }
    }

    framePick?.let { (file, duration) ->
        FramePickerDialog(file, duration, onDismiss = { framePick = null }) { img ->
            framePick = null
            c.setSource(img)
        }
    }
}

@Composable
private fun MaskEditor(src: ImageBitmap, c: ImageController, drawing: Boolean) {
    val current = remember { mutableStateListOf<Pair<Float, Float>>() }
    val maskColor = Color(0x88FF3B30)
    Box(Modifier.fillMaxWidth().aspectRatio(src.width.toFloat() / src.height).clip(RoundedCornerShape(10.dp))) {
        Image(src, "Исходник", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        val gestures = if (drawing) Modifier
            .pointerInput(Unit) {
                detectTapGestures { o -> c.strokes += Stroke(listOf(o.x / size.width to o.y / size.height), c.brush) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> current.clear(); current += o.x / size.width to o.y / size.height },
                    onDrag = { ch, _ -> current += ch.position.x / size.width to ch.position.y / size.height; ch.consume() },
                    onDragEnd = { if (current.isNotEmpty()) c.strokes += Stroke(current.toList(), c.brush); current.clear() },
                    onDragCancel = { current.clear() },
                )
            } else Modifier
        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val dim = maxOf(size.width, size.height)
            fun draw(points: List<Pair<Float, Float>>, r: Float) {
                val px = points.map { Offset(it.first * size.width, it.second * size.height) }
                if (px.size == 1) drawCircle(maskColor, r * dim, px[0])
                else for (i in 1 until px.size) drawLine(maskColor, px[i - 1], px[i], r * 2 * dim, StrokeCap.Round)
            }
            c.strokes.forEach { draw(it.points, it.radius) }
            if (current.isNotEmpty()) draw(current, c.brush)
            if (drawing) drawRect(Color(0xFFFF3B30), style = DrawStroke(width = 3f))
        }
    }
}

@Composable
private fun ColumnScope.ImageResultView(app: AppState, c: ImageController) {
    val cur = c.current
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (cur != null) {
            Image(cur.bitmap, "Результат", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Hint("Здесь появится результат. Все картинки сохраняются в папку «${Paths.outputs.absolutePath}».")
        }
    }
    if (cur != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Hint("seed ${cur.seed} · ${fmt(cur.seconds)} с · ${cur.image.width}×${cur.image.height}", Modifier.weight(1f))
            TextButton(onClick = { c.setSource(cur.image) }) { Text("Править дальше") }
            TextButton(onClick = {
                DesktopMedia.chooseSave("Сохранить", cur.file.name)?.let { f -> cur.file.copyTo(f, overwrite = true) }
            }) { Text("Сохранить как…") }
            TextButton(onClick = { DesktopMedia.openFolder(cur.file.parentFile) }) { Text("Открыть папку") }
        }
    }
    if (c.results.size > 1) {
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            c.results.forEach { r ->
                Image(
                    r.bitmap, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                        .then(if (r === cur) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable { c.current = r },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.VideoResultView(c: ImageController) {
    val v = c.videoResult
    if (v == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Hint(
                "Здесь появится видео. Генерация занимает от минуты до десятков минут — зависит от видеокарты, " +
                    "размера и числа кадров. Видео сохраняются в «${Paths.outputs.absolutePath}».",
            )
        }
        return
    }
    var index by remember(v) { mutableIntStateOf(0) }
    var playing by remember(v) { mutableStateOf(true) }
    LaunchedEffect(v, playing) {
        while (playing) {
            delay(1000L / v.fps.coerceAtLeast(1))
            index = (index + 1) % v.frames.size
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(v.frames[index.coerceIn(0, v.frames.lastIndex)], "Видео", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
    Slider(value = index.toFloat(), onValueChange = { playing = false; index = it.roundToInt() }, valueRange = 0f..maxOf(1, v.frames.lastIndex).toFloat())
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { playing = !playing }) { Text(if (playing) "⏸ Пауза" else "▶ Играть") }
        Hint("${v.frames.size} кадров · ${v.fps} к/с · ${fmt(v.seconds)} с генерации", Modifier.weight(1f))
        TextButton(onClick = { DesktopMedia.chooseSave("Сохранить видео", v.file.name)?.let { f -> v.file.copyTo(f, overwrite = true) } }) {
            Text("Сохранить как…")
        }
        TextButton(onClick = { DesktopMedia.openFolder(v.file.parentFile) }) { Text("Открыть папку") }
    }
}

@Composable
private fun FramePickerDialog(file: File, duration: Double, onDismiss: () -> Unit, onPick: (BufferedImage) -> Unit) {
    var pos by remember { mutableStateOf(0f) }
    var frame by remember { mutableStateOf<BufferedImage?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(pos) {
        frame = runCatching { DesktopMedia.videoFrame(file, pos * duration) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кадр из «${file.name}»") },
        text = {
            Column(Modifier.width(560.dp)) {
                val f = frame
                if (f != null) {
                    val bmp = remember(f) { f.toComposeImageBitmap() }
                    Image(bmp, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(320.dp))
                } else Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                Slider(value = pos, onValueChange = { pos = it })
                Hint("${fmt((pos * duration).toFloat())} с из ${fmt(duration.toFloat())} с")
            }
        },
        confirmButton = {
            TextButton(onClick = { frame?.let { f -> scope.launch { onPick(f) } } }, enabled = frame != null) { Text("Использовать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
