package com.neuropocket.app.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class LocalModel(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val parts: Int,
    val kind: ModelKind,
    /** Модуль зрения (mmproj) — если есть, модель понимает фото и видео. */
    val mmprojPath: String? = null,
) {
    val hasVision: Boolean get() = mmprojPath != null
}

data class DownloadInfo(
    val id: Long,
    val fileName: String,
    val downloaded: Long,
    val total: Long,
    val status: Status,
) {
    enum class Status { QUEUED, RUNNING, PAUSED }

    val progress: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
}

/**
 * Хранение моделей на устройстве: список, скачивание через системный
 * DownloadManager (работает в фоне, продолжает после обрыва сети),
 * импорт файла из памяти телефона и удаление.
 *
 * Чат-модели лежат в models/, модуль зрения — рядом, как «<модель>.gguf.mmproj».
 * Модели для картинок лежат в sd_models/.
 */
class ModelRepository(private val context: Context) {

    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val splitRegex = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

    private fun dir(name: String): File =
        (context.getExternalFilesDir(name) ?: File(context.filesDir, name)).also { it.mkdirs() }

    val modelsDir: File get() = dir(DIR_CHAT)
    val imageModelsDir: File get() = dir(DIR_IMAGE)

    fun dirFor(kind: ModelKind): File = if (kind == ModelKind.CHAT) modelsDir else imageModelsDir

    // ---------- Установленные модели ----------

    fun installedModels(): List<LocalModel> {
        val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }
            ?: return emptyList()
        val result = mutableListOf<LocalModel>()
        for (f in files) {
            val m = splitRegex.matchEntire(f.name)
            val mmproj = File(modelsDir, f.name + MMPROJ_SUFFIX).takeIf { it.exists() }?.absolutePath
            if (m == null) {
                result += LocalModel(f.name, f.absolutePath, f.length(), 1, ModelKind.CHAT, mmproj)
            } else if (m.groupValues[2] == "00001") {
                val siblings = files.filter { it.name.startsWith(m.groupValues[1] + "-") && splitRegex.matches(it.name) }
                result += LocalModel(
                    name = m.groupValues[1] + ".gguf",
                    path = f.absolutePath,
                    sizeBytes = siblings.sumOf { it.length() },
                    parts = siblings.size,
                    kind = ModelKind.CHAT,
                    mmprojPath = mmproj,
                )
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun installedImageModels(): List<LocalModel> {
        val files = imageModelsDir.listFiles { f ->
            f.isFile && IMAGE_EXT.any { f.name.endsWith(it, true) }
        } ?: return emptyList()
        return files.map { LocalModel(it.name, it.absolutePath, it.length(), 1, ModelKind.IMAGE) }
            .sortedBy { it.name.lowercase() }
    }

    fun delete(model: LocalModel) {
        val file = File(model.path)
        model.mmprojPath?.let { File(it).delete() }
        val m = splitRegex.matchEntire(file.name)
        if (model.kind == ModelKind.IMAGE || m == null) {
            file.delete()
        } else {
            modelsDir.listFiles()?.filter { it.name.startsWith(m.groupValues[1] + "-") }?.forEach { it.delete() }
        }
    }

    // ---------- Скачивание ----------

    /** id загрузки → "папка/имя файла" */
    private fun tracked(): MutableMap<Long, String> {
        val json = JSONObject(prefs.getString("map", "{}") ?: "{}")
        return json.keys().asSequence().associate { key ->
            val v = json.getString(key)
            key.toLong() to (if ('/' in v) v else "$DIR_CHAT/$v")
        }.toMutableMap()
    }

    private fun saveTracked(map: Map<Long, String>) = prefs.edit {
        putString("map", JSONObject(map.mapKeys { it.key.toString() }).toString())
    }

    private fun targetFile(rel: String): File = File(dir(rel.substringBefore('/')), rel.substringAfter('/'))

    /** Ставит в очередь все части модели и её модуль зрения. */
    fun enqueue(file: HfModelFile, hfToken: String) {
        val map = tracked()
        val dirName = if (file.kind == ModelKind.CHAT) DIR_CHAT else DIR_IMAGE
        val jobs = mutableListOf<Pair<String, String>>() // путь в репо → имя файла
        file.parts.forEach { jobs += it to it.substringAfterLast('/') }
        if (file.mmproj != null) {
            jobs += file.mmproj to file.parts.first().substringAfterLast('/') + MMPROJ_SUFFIX
        }
        for ((path, name) in jobs) {
            val rel = "$dirName/$name"
            if (targetFile(rel).exists() || map.containsValue(rel)) continue
            File(dir(dirName), "$name.part").delete()
            val req = DownloadManager.Request(HuggingFace.downloadUrl(file.repo, path).toUri())
                .setTitle(name)
                .setDescription("Модель для NeuroPocket")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(context, dirName, "$name.part")
            if (hfToken.isNotBlank()) req.addRequestHeader("Authorization", "Bearer ${hfToken.trim()}")
            map[dm.enqueue(req)] = rel
        }
        saveTracked(map)
    }

    fun cancel(id: Long) {
        dm.remove(id)
        val map = tracked()
        map.remove(id)?.let { File(targetFile(it).path + ".part").delete() }
        saveTracked(map)
    }

    /**
     * Проверяет состояние загрузок: завершённые переименовывает,
     * неудачные удаляет. Возвращает активные загрузки и сообщения об ошибках.
     */
    fun syncDownloads(): Pair<List<DownloadInfo>, List<String>> {
        val map = tracked()
        if (map.isEmpty()) return emptyList<DownloadInfo>() to emptyList()
        val active = mutableListOf<DownloadInfo>()
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<Long>()

        val query = DownloadManager.Query().setFilterById(*map.keys.toLongArray())
        dm.query(query)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val soFarCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val rel = map[id] ?: continue
                val target = targetFile(rel)
                val part = File(target.path + ".part")
                seen += id
                when (c.getInt(statusCol)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (!part.renameTo(target)) errors += "Не удалось сохранить ${target.name}"
                        map.remove(id)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        errors += "Ошибка загрузки ${target.name} (${failReason(c.getInt(reasonCol))})"
                        dm.remove(id)
                        part.delete()
                        map.remove(id)
                    }
                    else -> {
                        val status = when (c.getInt(statusCol)) {
                            DownloadManager.STATUS_RUNNING -> DownloadInfo.Status.RUNNING
                            DownloadManager.STATUS_PAUSED -> DownloadInfo.Status.PAUSED
                            else -> DownloadInfo.Status.QUEUED
                        }
                        active += DownloadInfo(id, target.name, c.getLong(soFarCol), c.getLong(totalCol), status)
                    }
                }
            }
        }
        // Загрузки, отменённые из шторки уведомлений, исчезают из DownloadManager
        (map.keys - seen).forEach { id -> map.remove(id)?.let { File(targetFile(it).path + ".part").delete() } }
        saveTracked(map)
        return active to errors
    }

    private fun failReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "недостаточно места"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "обрыв соединения"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "слишком много перенаправлений"
        401, 403 -> "нет доступа — нужен токен Hugging Face"
        404 -> "файл не найден"
        else -> "код $reason"
    }

    // ---------- Импорт из памяти телефона ----------

    private fun displayName(uri: Uri): Pair<String, Long> {
        var name = "model"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_") to size
    }

    private fun copy(uri: Uri, target: File, size: Long, checkGguf: Boolean, onProgress: (Float) -> Unit) {
        val tmp = File(target.path + ".import")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(4)
                val read = input.read(head)
                if (checkGguf && (read != 4 || String(head, Charsets.US_ASCII) != "GGUF")) {
                    throw IOException("Это не GGUF-файл. Чат-модели поддерживаются в формате .gguf")
                }
                tmp.outputStream().use { out ->
                    if (read > 0) out.write(head, 0, read)
                    val buf = ByteArray(1 shl 20)
                    var copied = read.toLong().coerceAtLeast(0)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (size > 0) onProgress(copied.toFloat() / size)
                    }
                }
            } ?: throw IOException("Не удалось открыть файл")
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить файл")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Копирует выбранный пользователем файл модели в папку моделей. */
    suspend fun import(uri: Uri, kind: ModelKind, onProgress: (Float) -> Unit): LocalModel =
        withContext(Dispatchers.IO) {
            var (name, size) = displayName(uri)
            if (kind == ModelKind.CHAT) {
                if (name.contains("mmproj", ignoreCase = true)) {
                    throw IOException("Это модуль зрения (mmproj). Подключите его через меню модели «Подключить зрение».")
                }
                if (!name.endsWith(".gguf", true)) name += ".gguf"
            } else if (IMAGE_EXT.none { name.endsWith(it, true) }) {
                throw IOException("Поддерживаются файлы .safetensors, .ckpt и .gguf")
            }
            val target = File(dirFor(kind), name)
            copy(uri, target, size, checkGguf = kind == ModelKind.CHAT, onProgress)
            LocalModel(target.name, target.absolutePath, target.length(), 1, kind)
        }

    /** Подключает к чат-модели файл модуля зрения (mmproj-*.gguf). */
    suspend fun importMmproj(model: LocalModel, uri: Uri, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val (_, size) = displayName(uri)
            val target = File(File(model.path).parentFile, File(model.path).name + MMPROJ_SUFFIX)
            copy(uri, target, size, checkGguf = true, onProgress)
        }

    companion object {
        const val DIR_CHAT = "models"
        const val DIR_IMAGE = "sd_models"
        const val MMPROJ_SUFFIX = ".mmproj"
        val IMAGE_EXT = listOf(".safetensors", ".ckpt", ".gguf")
    }
}
