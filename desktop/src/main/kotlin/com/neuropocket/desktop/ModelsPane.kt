package com.neuropocket.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neuropocket.app.data.HfModelFile
import com.neuropocket.app.data.ModelKind
import com.neuropocket.app.data.SUGGESTED_CHAT
import com.neuropocket.app.data.SUGGESTED_VISION
import com.neuropocket.app.data.Suggested
import com.neuropocket.sd.SdModelFiles
import java.io.File

/** Дополнительные чат-модели для компьютера (крупнее, чем для телефона). */
private val SUGGESTED_DESKTOP = listOf(
    Suggested("unsloth/Qwen3-8B-GGUF", "Qwen 3 · 8B", "≈5 ГБ · отличный русский, рассуждения", ModelKind.CHAT),
    Suggested("unsloth/Qwen3-14B-GGUF", "Qwen 3 · 14B", "≈9 ГБ · видеокарта от 12 ГБ", ModelKind.CHAT),
    Suggested("unsloth/Qwen3-30B-A3B-GGUF", "Qwen 3 · 30B-A3B (MoE)", "≈18 ГБ · быстрая даже частично на процессоре", ModelKind.CHAT),
    Suggested("unsloth/gemma-3-12b-it-GGUF", "👁 Gemma 3 · 12B", "≈7 ГБ + зрение", ModelKind.CHAT),
    Suggested("unsloth/gemma-3-27b-it-GGUF", "👁 Gemma 3 · 27B", "≈17 ГБ + зрение · видеокарта 24 ГБ", ModelKind.CHAT),
    Suggested("ggml-org/Qwen2.5-VL-7B-Instruct-GGUF", "👁 Qwen2.5-VL · 7B", "≈5 ГБ · фото, видео, текст на фото", ModelKind.CHAT),
    Suggested("unsloth/Mistral-Small-3.2-24B-Instruct-2506-GGUF", "👁 Mistral Small 3.2 · 24B", "≈14 ГБ", ModelKind.CHAT),
    Suggested("bartowski/Meta-Llama-3.1-8B-Instruct-GGUF", "Llama 3.1 · 8B", "≈5 ГБ", ModelKind.CHAT),
    Suggested("unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF", "DeepSeek R1 · 8B", "≈5 ГБ · рассуждения", ModelKind.CHAT),
    Suggested("unsloth/Qwen2.5-Coder-7B-Instruct-GGUF", "👨‍💻 Qwen2.5-Coder · 7B", "≈5 ГБ · программирование", ModelKind.CHAT),
    Suggested("unsloth/Qwen2.5-Coder-14B-Instruct-GGUF", "👨‍💻 Qwen2.5-Coder · 14B", "≈9 ГБ · программирование, видеокарта от 12 ГБ", ModelKind.CHAT),
    Suggested("unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF", "👨‍💻 Qwen3-Coder · 30B-A3B", "≈18 ГБ · лучший код, быстрая (MoE)", ModelKind.CHAT),
    Suggested("unsloth/gpt-oss-20b-GGUF", "gpt-oss · 20B", "≈12 ГБ · модель OpenAI с открытыми весами", ModelKind.CHAT),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelsPane(app: AppState) {
    if (app.openRepo != null) {
        RepoView(app)
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Модели", style = MaterialTheme.typography.titleLarge)
                Hint("Папка: ${Paths.root.absolutePath}")
            }
            TextButton(onClick = { DesktopMedia.openFolder(Paths.root) }) { Text("📂 Открыть папку") }
            TextButton(onClick = { app.refreshModels() }) { Text("↻ Обновить") }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Чат и зрение", "Картинки и видео", "Речь", "Любой репозиторий").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            DownloadsSection(app)
            when (tab) {
                0 -> ChatModelsTab(app)
                1 -> ImageModelsTab(app)
                2 -> SpeechModelsSection(app)
                else -> AnyRepoTab(app)
            }
        }
    }
}

@Composable
private fun DownloadsSection(app: AppState) {
    if (app.downloads.isEmpty()) return
    SectionTitle("Загрузки")
    app.downloads.forEach { d ->
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.progress >= 0) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Hint("${d.stage} · ${formatSize(d.done)}${if (d.total > 0) " из ${formatSize(d.total)}" else ""}")
                }
                TextButton(onClick = { app.cancelDownload(d) }) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun FileRow(name: String, info: String, active: Boolean = false, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (active) "● " else "") + name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint(info)
            }
            actions()
        }
    }
}

// ============================ Чат ============================

@Composable
private fun ChatModelsTab(app: AppState) {
    SectionTitle("На компьютере")
    if (app.chatModels.isEmpty()) Hint("Пока нет чат-моделей. Скачайте одну ниже или откройте .gguf-файл с диска.")
    app.chatModels.forEach { f ->
        val mm = app.mmprojFor(f)
        FileRow(
            (if (mm != null) "👁 " else "") + f.name,
            formatSize(f.length()) + (if (mm != null) " · видит фото и видео" else ""),
            active = app.settings.chatModel == f.absolutePath,
        ) {
            TextButton(onClick = { app.chat.select(f); app.tab = Tab.CHAT }) { Text("В чат") }
            TextButton(onClick = {
                DesktopMedia.chooseFile("Модуль зрения (mmproj)", listOf("gguf"), "GGUF")?.let { src ->
                    src.copyTo(File(f.path + ".mmproj"), overwrite = true)
                    app.refreshModels()
                    app.toast("Зрение подключено")
                }
            }) { Text(if (mm != null) "Заменить зрение" else "Подключить зрение") }
            TextButton(onClick = { app.deleteFile(f) }) { Text("Удалить") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton(onClick = {
            DesktopMedia.chooseFile("Чат-модель GGUF", listOf("gguf"), "GGUF")?.let { app.chat.select(it); app.tab = Tab.CHAT }
        }) { Text("Открыть .gguf с диска") }
    }
    Hint("Поддерживается любая модель в формате GGUF, которую понимает llama.cpp: Llama, Qwen, Gemma, Mistral, DeepSeek, Phi, gpt-oss и сотни других, включая дообученные версии.")

    SectionTitle("Поиск на Hugging Face")
    HfSearch(app, ModelKind.CHAT)

    SectionTitle("Рекомендуемые для компьютера")
    (SUGGESTED_DESKTOP + SUGGESTED_VISION + SUGGESTED_CHAT).forEach { s ->
        FileRow(s.title, "${s.note} · ${s.repo}") {
            TextButton(onClick = { app.openRepository(s.repo) }) { Text("Выбрать файл →") }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HfSearch(app: AppState, kind: ModelKind) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = app.hfQuery, onValueChange = { app.hfQuery = it }, singleLine = true,
            placeholder = { Text(if (kind == ModelKind.CHAT) "qwen, llama, gemma… или автор/репозиторий" else "flux, wan, sdxl, whisper… или автор/репозиторий") },
            modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { go(app, kind); true } else false
            },
        )
        Spacer(Modifier.size(8.dp))
        Button(onClick = { go(app, kind) }) { Text("Найти") }
        if (app.hfLoading) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(24.dp), strokeWidth = 2.dp)
    }
    if (app.hfKind == kind) {
        app.hfResults.forEach { r ->
            FileRow(r.id, "⬇ ${r.downloads} · ♥ ${r.likes}") {
                TextButton(onClick = { app.openRepository(r.id) }) { Text("Открыть →") }
            }
        }
    }
}

private fun go(app: AppState, kind: ModelKind) {
    val q = app.hfQuery.trim()
    app.hfKind = kind
    if (q.count { it == '/' } == 1 && ' ' !in q || q.startsWith("https://huggingface.co/")) app.openRepository(q) else app.searchHf()
}

// ============================ Картинки и видео ============================

@Composable
private fun ImageModelsTab(app: AppState) {
    var editing by remember { mutableStateOf<SdProfile?>(null) }

    SectionTitle("Готовые наборы (скачиваются одной кнопкой)")
    Hint("Большие модели состоят из нескольких файлов — набор скачает все и настроит профиль. Указан примерный объём видеопамяти.")
    PRESETS.forEach { p ->
        val have = app.profiles.any { it.name == p.profile.name }
        FileRow(
            (if (p.video) "🎬 " else "🎨 ") + p.title,
            "${p.note} · видеокарта ≈${p.vramGb} ГБ" + (if (have) " · профиль создан" else ""),
        ) {
            FilledTonalButton(onClick = { app.installPreset(p) }) { Text(if (have) "Докачать" else "Скачать") }
        }
    }

    SectionTitle("Профили моделей")
    if (app.profiles.isEmpty()) Hint("Профилей пока нет. Скачайте набор выше или создайте профиль из своих файлов.")
    app.profiles.forEach { p ->
        val missing = p.files.toMap().values.filterNotNull().count { !File(it).exists() }
        FileRow(
            (if (p.video) "🎬 " else "🎨 ") + p.name,
            p.files.toMap().filterValues { !it.isNullOrBlank() }.keys.joinToString(", ") +
                (if (missing > 0) " · ⚠ не скачано файлов: $missing" else ""),
        ) {
            TextButton(onClick = {
                if (p.video) { app.video.select(p); app.tab = Tab.VIDEO } else { app.images.select(p); app.tab = Tab.IMAGES }
            }, enabled = missing == 0) { Text("Открыть") }
            TextButton(onClick = { editing = p }) { Text("Изменить") }
            TextButton(onClick = { app.deleteProfile(p) }) { Text("Удалить") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton(onClick = { editing = SdProfile("Моя модель", false, SdModelFiles()) }) { Text("＋ Профиль картинок") }
        OutlinedButton(onClick = { editing = SdProfile("Моя видеомодель", true, SdModelFiles(), width = 832, height = 480, cfg = 6f, sampler = "euler") }) {
            Text("＋ Профиль видео")
        }
    }
    Hint(
        "Поддерживаются модели stable-diffusion.cpp: SD 1.x/2.x, SDXL, SD3/3.5, FLUX.1/FLUX.2, Chroma, Z-Image, Qwen-Image (и Edit), " +
            "HiDream, PixArt, Wan 2.1/2.2, LTX-Video и другие — в форматах .safetensors, .gguf, .ckpt.",
    )

    SectionTitle("Файлы в папке картинок и видео")
    if (app.imageFiles.isEmpty()) Hint("Пусто.")
    app.imageFiles.forEach { f ->
        FileRow(f.name, "${formatSize(f.length())} · ${f.parentFile.name}") {
            TextButton(onClick = { app.deleteFile(f) }) { Text("Удалить") }
        }
    }

    SectionTitle("Поиск на Hugging Face")
    HfSearch(app, ModelKind.IMAGE)

    editing?.let { p -> ProfileEditor(app, p, onDismiss = { editing = null }) }
}

@Composable
private fun ProfileEditor(app: AppState, initial: SdProfile, onDismiss: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.video) "Профиль видеомодели" else "Профиль модели картинок") },
        text = {
            Column(Modifier.width(720.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Hint("Простые модели (SD 1.5, SDXL) — один файл «Модель целиком». Большие — диффузионная модель + VAE + текстовые энкодеры.",
                    Modifier.padding(vertical = 6.dp))
                val map = p.files.toMap()
                COMPONENT_ROLES.forEach { (role, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(title, Modifier.width(250.dp), style = MaterialTheme.typography.bodyMedium)
                        val cur = map[role]
                        Dropdown(
                            "—", listOf<File?>(null) + app.imageFiles, cur?.let { File(it) },
                            { it?.name ?: "—" }, Modifier.weight(1f),
                        ) { f -> p = p.copy(files = p.files.with(role, f?.absolutePath)) }
                        TextButton(onClick = {
                            DesktopMedia.chooseFile(title, listOf("safetensors", "gguf", "ckpt", "sft", "pt", "pth", "bin"), "Модели")
                                ?.let { f -> p = p.copy(files = p.files.with(role, f.absolutePath)) }
                        }) { Text("Файл…") }
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(p.steps.toString(), { v -> v.toIntOrNull()?.let { p = p.copy(steps = it) } }, label = { Text("Шаги") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(fmt(p.cfg), { v -> v.replace(',', '.').toFloatOrNull()?.let { p = p.copy(cfg = it) } }, label = { Text("CFG") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(p.width.toString(), { v -> v.toIntOrNull()?.let { p = p.copy(width = it) } }, label = { Text("Ширина") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(p.height.toString(), { v -> v.toIntOrNull()?.let { p = p.copy(height = it) } }, label = { Text("Высота") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(p.sampler, { p = p.copy(sampler = it.trim()) }, label = { Text("Сэмплер (пусто — авто)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.video, { p = p.copy(video = it) }); Text("Видеомодель")
                    Spacer(Modifier.width(16.dp))
                    Checkbox(p.canEdit, { p = p.copy(canEdit = it) }); Text("Умеет редактировать по инструкции")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (p.name != initial.name) app.deleteProfile(initial)
                app.saveProfile(p)
                onDismiss()
            }, enabled = p.name.isNotBlank() && p.files.mainFile != null) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ============================ Любой репозиторий ============================

@Composable
private fun AnyRepoTab(app: AppState) {
    Hint("Найдите любой репозиторий Hugging Face и скачайте нужные файлы: модели GGUF для чата, " +
        "веса для картинок и видео, LoRA, VAE, энкодеры — без ограничений.")
    Spacer(Modifier.height(8.dp))
    HfSearch(app, ModelKind.IMAGE)
}

@Composable
private fun RepoView(app: AppState) {
    val repo = app.openRepo ?: return
    var filter by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = app::closeRepository) { Text("← Назад") }
            Column(Modifier.weight(1f)) {
                Text(repo, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint("Оперативная память: ${formatSize(app.totalRamBytes)}")
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            DownloadsSection(app)
            if (app.repoLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (app.repoChatFiles.isNotEmpty()) {
                SectionTitle("Чат-модель (GGUF)")
                if (app.repoChatFiles.any { it.mmproj != null }) Hint("👁 Модуль зрения скачается вместе с моделью.")
                app.repoChatFiles.forEach { f: HfModelFile ->
                    val extra = if (f.mmprojSize > 0) " + ${formatSize(f.mmprojSize)} зрение" else ""
                    FileRow(f.displayName, formatSize(f.sizeBytes) + extra + (if (f.isRecommendedQuant) " · рекомендуется" else "")) {
                        FilledTonalButton(onClick = { app.downloadChatModel(f) }) { Text("Скачать в чат") }
                    }
                }
            }
            SectionTitle("Все файлы")
            OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Фильтр по имени") }, modifier = Modifier.fillMaxWidth())
            val files = app.repoAllFiles.filter { filter.isBlank() || it.path.contains(filter, true) }
            files.take(400).forEach { f ->
                FileRow(f.path, formatSize(f.size)) {
                    if (f.path.endsWith(".gguf", true)) {
                        TextButton(onClick = { app.downloadRepoFile(repo, f.path, toChat = true) }) { Text("В чат") }
                    }
                    TextButton(onClick = { app.downloadRepoFile(repo, f.path, toChat = false) }) { Text("В картинки/видео") }
                }
            }
            if (files.size > 400) Hint("Показаны первые 400 файлов — уточните фильтр.")
        }
    }
}
