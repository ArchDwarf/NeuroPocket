package com.neuropocket.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neuropocket.app.data.DownloadInfo
import com.neuropocket.app.data.HfModelFile
import com.neuropocket.app.data.LocalModel
import com.neuropocket.app.data.SUGGESTED_MODELS
import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f ГБ", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f МБ", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} КБ"
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
    else -> n.toString()
}

@Composable
fun ModelsScreen(vm: MainViewModel, modifier: Modifier) {
    if (vm.openRepo != null) {
        BackHandler { vm.closeRepository() }
        RepoFilesScreen(vm, modifier)
    } else {
        ModelsHome(vm, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsHome(vm: MainViewModel, modifier: Modifier) {
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    var toDelete by remember { mutableStateOf<LocalModel?>(null) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Модели") },
            actions = {
                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.size(4.dp))
                    Text("Из файла")
                }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ----- Установленные -----
            section("На устройстве")
            vm.importProgress?.let { p ->
                item {
                    Column {
                        Text("Копирование файла… ${(p * 100).toInt()}%")
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (vm.installed.isEmpty() && vm.downloads.isEmpty()) {
                item {
                    Text(
                        "Пока нет моделей. Скачайте одну из списка ниже, найдите любую на " +
                            "Hugging Face или добавьте .gguf-файл из памяти телефона.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(vm.installed, key = { "local:" + it.path }) { model ->
                InstalledCard(
                    model = model,
                    active = vm.settings.activeModelPath == model.path,
                    onSelect = { vm.selectModel(model) },
                    onDelete = { toDelete = model },
                )
            }
            items(vm.downloads, key = { "dl:" + it.id }) { dl ->
                DownloadCard(dl) { vm.cancelDownload(dl.id) }
            }

            // ----- Поиск -----
            section("Найти на Hugging Face")
            item { SearchBox(vm) }
            if (vm.searchLoading) item { CenteredProgress() }
            vm.searchError?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error) } }
            items(vm.searchResults, key = { "hf:" + it.id }) { r ->
                RepoRow(
                    title = r.id,
                    subtitle = "⬇ ${formatCount(r.downloads)} · ♥ ${formatCount(r.likes)}",
                ) { vm.openRepository(r.id) }
            }

            // ----- Рекомендуемые -----
            section("Рекомендуемые для телефона")
            items(SUGGESTED_MODELS, key = { "s:" + it.repo }) { s ->
                RepoRow(title = s.title, subtitle = "${s.note}\n${s.repo}") { vm.openRepository(s.repo) }
            }
        }
    }

    toDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить модель?") },
            text = { Text("${model.name}\nОсвободится ${formatSize(model.sizeBytes)}.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteModel(model); toDelete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

private fun LazyListScope.section(title: String) {
    item(key = "section:$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun CenteredProgress() {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun InstalledCard(model: LocalModel, active: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onSelect,
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(model.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val parts = if (model.parts > 1) " · ${model.parts} части" else ""
                Text(
                    formatSize(model.sizeBytes) + parts,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
        }
    }
}

@Composable
private fun DownloadCard(dl: DownloadInfo, onCancel: () -> Unit) {
    Card {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dl.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(6.dp))
                if (dl.total > 0) {
                    LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val status = when (dl.status) {
                    DownloadInfo.Status.QUEUED -> "в очереди"
                    DownloadInfo.Status.PAUSED -> "пауза (ожидание сети)"
                    DownloadInfo.Status.RUNNING -> "загрузка"
                }
                val sizes = if (dl.total > 0) "${formatSize(dl.downloaded)} из ${formatSize(dl.total)}" else ""
                Text(
                    "$status  $sizes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Отменить") }
        }
    }
}

@Composable
private fun SearchBox(vm: MainViewModel) {
    fun go() {
        val q = vm.searchQuery.trim()
        // Если введён путь репозитория (автор/модель) — сразу открываем его
        if (q.count { it == '/' } == 1 && !q.contains(' ')) vm.openRepository(q) else vm.search()
    }
    OutlinedTextField(
        value = vm.searchQuery,
        onValueChange = { vm.searchQuery = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("qwen, llama, gemma… или автор/репозиторий") },
        trailingIcon = { IconButton(onClick = { go() }) { Icon(Icons.Default.Search, "Искать") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { go() }),
    )
}

@Composable
private fun RepoRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoFilesScreen(vm: MainViewModel, modifier: Modifier) {
    val repo = vm.openRepo ?: return
    val installedNames = vm.installed.map { it.path.substringAfterLast('/') }.toSet()
    val downloadingNames = vm.downloads.map { it.fileName }.toSet()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(repo, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = vm::closeRepository) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Выберите вариант квантования. Q4_K_M — лучший баланс качества и скорости. " +
                        "Чем больше файл, тем умнее модель, но медленнее и тем больше нужно памяти. " +
                        "ОЗУ телефона: ${formatSize(vm.totalRamBytes)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.repoLoading) item { CenteredProgress() }
            vm.repoError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(vm.repoFiles, key = { it.parts.first() }) { f ->
                val firstName = f.parts.first().substringAfterLast('/')
                val state = when {
                    firstName in installedNames -> FileState.INSTALLED
                    f.parts.any { it.substringAfterLast('/') in downloadingNames } -> FileState.DOWNLOADING
                    else -> FileState.NONE
                }
                FileCard(f, vm.totalRamBytes, state) { vm.download(f) }
            }
        }
    }
}

private enum class FileState { NONE, DOWNLOADING, INSTALLED }

@Composable
private fun FileCard(f: HfModelFile, ram: Long, state: FileState, onDownload: () -> Unit) {
    val tooBig = f.sizeBytes > ram * 0.75
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(f.displayName, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatSize(f.sizeBytes), style = MaterialTheme.typography.bodySmall)
                if (f.isRecommendedQuant) {
                    Spacer(Modifier.size(8.dp))
                    AssistChip(onClick = {}, label = { Text("рекомендуется") })
                }
                Spacer(Modifier.weight(1f))
                when (state) {
                    FileState.INSTALLED -> Text("✓ установлена", color = MaterialTheme.colorScheme.primary)
                    FileState.DOWNLOADING -> Text("загружается…")
                    FileState.NONE -> FilledTonalButton(onClick = onDownload) { Text("Скачать") }
                }
            }
            if (tooBig) {
                Text(
                    "⚠ Модель больше 75% оперативной памяти — может не запуститься или работать очень медленно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
