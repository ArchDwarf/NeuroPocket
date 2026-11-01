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
)

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
 */
class ModelRepository(private val context: Context) {

    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val splitRegex = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

    val modelsDir: File
        get() = (context.getExternalFilesDir(DIR) ?: File(context.filesDir, DIR)).also { it.mkdirs() }

    // ---------- Установленные модели ----------

    fun installedModels(): List<LocalModel> {
        val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }
            ?: return emptyList()
        val result = mutableListOf<LocalModel>()
        for (f in files) {
            val m = splitRegex.matchEntire(f.name)
            if (m == null) {
                result += LocalModel(f.name, f.absolutePath, f.length(), 1)
            } else if (m.groupValues[2] == "00001") {
                val siblings = files.filter { it.name.startsWith(m.groupValues[1] + "-") && splitRegex.matches(it.name) }
                result += LocalModel(
                    name = m.groupValues[1] + ".gguf",
                    path = f.absolutePath,
                    sizeBytes = siblings.sumOf { it.length() },
                    parts = siblings.size,
                )
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun delete(model: LocalModel) {
        val file = File(model.path)
        val m = splitRegex.matchEntire(file.name)
        if (m == null) {
            file.delete()
        } else {
            modelsDir.listFiles()?.filter { it.name.startsWith(m.groupValues[1] + "-") }?.forEach { it.delete() }
        }
    }

    // ---------- Скачивание ----------

    private fun tracked(): MutableMap<Long, String> {
        val json = JSONObject(prefs.getString("map", "{}") ?: "{}")
        return json.keys().asSequence().associate { it.toLong() to json.getString(it) }.toMutableMap()
    }

    private fun saveTracked(map: Map<Long, String>) = prefs.edit {
        putString("map", JSONObject(map.mapKeys { it.key.toString() }).toString())
    }

    /** Ставит в очередь все части модели. */
    fun enqueue(file: HfModelFile, hfToken: String) {
        val map = tracked()
        for (path in file.parts) {
            val name = path.substringAfterLast('/')
            if (File(modelsDir, name).exists() || map.containsValue(name)) continue
            File(modelsDir, "$name.part").delete()
            val req = DownloadManager.Request(HuggingFace.downloadUrl(file.repo, path).toUri())
                .setTitle(name)
                .setDescription("Модель для NeuroPocket")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(context, DIR, "$name.part")
            if (hfToken.isNotBlank()) req.addRequestHeader("Authorization", "Bearer ${hfToken.trim()}")
            map[dm.enqueue(req)] = name
        }
        saveTracked(map)
    }

    fun cancel(id: Long) {
        dm.remove(id)
        val map = tracked()
        map.remove(id)?.let { File(modelsDir, "$it.part").delete() }
        saveTracked(map)
    }

    /**
     * Проверяет состояние загрузок: завершённые переименовывает в .gguf,
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
                val name = map[id] ?: continue
                seen += id
                when (c.getInt(statusCol)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val part = File(modelsDir, "$name.part")
                        if (!part.renameTo(File(modelsDir, name))) errors += "Не удалось сохранить $name"
                        map.remove(id)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(reasonCol)
                        errors += "Ошибка загрузки $name (${failReason(reason)})"
                        dm.remove(id)
                        File(modelsDir, "$name.part").delete()
                        map.remove(id)
                    }
                    else -> {
                        val status = when (c.getInt(statusCol)) {
                            DownloadManager.STATUS_RUNNING -> DownloadInfo.Status.RUNNING
                            DownloadManager.STATUS_PAUSED -> DownloadInfo.Status.PAUSED
                            else -> DownloadInfo.Status.QUEUED
                        }
                        active += DownloadInfo(id, name, c.getLong(soFarCol), c.getLong(totalCol), status)
                    }
                }
            }
        }
        // Загрузки, отменённые из шторки уведомлений, исчезают из DownloadManager
        (map.keys - seen).forEach { id -> map.remove(id)?.let { File(modelsDir, "$it.part").delete() } }
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

    /** Копирует выбранный пользователем .gguf в папку моделей. */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): LocalModel = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "model.gguf"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        name = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (!name.endsWith(".gguf", true)) name += ".gguf"

        val target = File(modelsDir, name)
        val tmp = File(modelsDir, "$name.import")
        try {
            resolver.openInputStream(uri)?.use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                    throw IOException("Это не GGUF-файл. Поддерживаются модели в формате .gguf")
                }
                tmp.outputStream().use { out ->
                    out.write(magic)
                    val buf = ByteArray(1 shl 20)
                    var copied = 4L
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
        LocalModel(target.name, target.absolutePath, target.length(), 1)
    }

    companion object {
        const val DIR = "models"
    }
}
