package com.neuropocket.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Простой загрузчик файлов с докачкой (.part + Range) и прогрессом.
 * Работает и на Android, и на компьютере.
 */
object HttpDownloader {

    /**
     * Скачивает [url] в [target]. [onProgress] получает (скачано, всего; всего = -1, если неизвестно).
     * Отмена — через отмену корутины; недокачанный .part остаётся для докачки.
     */
    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var attempt = 0
        while (true) {
            try {
                fetch(url, part, headers, onProgress)
                break
            } catch (e: IOException) {
                // Обрыв сети — пробуем продолжить с того же места
                if (++attempt >= 5) throw e
                currentCoroutineContext().ensureActive()
                delay(2000L * attempt)
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Не удалось сохранить ${target.name}")
        target
    }

    private suspend fun fetch(
        url: String,
        part: File,
        headers: Map<String, String>,
        onProgress: (Long, Long) -> Unit,
    ) {
        var current = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        val have = if (part.exists()) part.length() else 0L
        while (true) {
            conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "NeuroPocket")
            // Токен отправляем только на исходный сайт, не на сторонние CDN
            if (redirects == 0) headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("Перенаправление без адреса")
                conn.disconnect()
                current = URL(current, loc)
                if (++redirects > 10) throw IOException("Слишком много перенаправлений")
                continue
            }
            if (code == 416) { // уже скачано полностью
                conn.disconnect()
                return
            }
            if (code == 401 || code == 403) {
                throw SecurityException("Нет доступа (HTTP $code). Для закрытых моделей укажите токен Hugging Face в настройках и примите лицензию модели на сайте.")
            }
            if (code == 404) throw SecurityException("Файл не найден (HTTP 404)")
            if (code !in 200..299) throw IOException("HTTP $code")
            break
        }
        val append = conn.responseCode == 206
        val start = if (append) have else 0L
        val length = conn.getHeaderFieldLong("Content-Length", -1L)
        val total = if (length >= 0) start + length else -1L
        conn.inputStream.use { input ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = start
                var lastReport = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 200) {
                        onProgress(done, total)
                        lastReport = now
                    }
                }
                onProgress(done, total)
                if (total > 0 && done < total) throw IOException("Соединение прервано")
            }
        }
        conn.disconnect()
    }

    /** Распаковывает .tar.bz2 в [destDir]. Возвращает папку верхнего уровня. */
    suspend fun extractTarBz2(archive: File, destDir: File, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            destDir.mkdirs()
            val canonicalDest = destDir.canonicalPath + File.separator
            var top: File? = null
            val total = archive.length().toFloat()
            val counting = CountingStream(BufferedInputStream(archive.inputStream(), 1 shl 16))
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val out = File(destDir, entry.name)
                    // Защита от путей вида ../../ в архиве
                    if (!out.canonicalPath.startsWith(canonicalDest)) continue
                    if (top == null) top = File(destDir, entry.name.substringBefore('/'))
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else if (entry.isFile) {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                    }
                    onProgress((counting.count / total).coerceIn(0f, 1f))
                }
            }
            top ?: destDir
        }

    private class CountingStream(private val inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    /** Скачивает и распаковывает речевую модель в [speechDir]. */
    suspend fun installSpeechModel(
        url: String,
        speechDir: File,
        onStatus: (String, Float) -> Unit,
    ): SpeechModel {
        val archive = File(speechDir, url.substringAfterLast('/'))
        download(url, archive) { done, total ->
            onStatus("Загрузка", if (total > 0) done.toFloat() / total else -1f)
        }
        onStatus("Распаковка", 0f)
        val dir = try {
            extractTarBz2(archive, speechDir) { onStatus("Распаковка", it) }
        } finally {
            archive.delete()
        }
        return SpeechModel.detect(dir) ?: throw IOException("Не удалось распознать тип модели в архиве")
    }
}

/** Чтение и запись WAV (16 бит PCM). */
object Wav {
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val data = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            data[i * 2] = (v and 0xFF).toByte()
            data[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }
        file.outputStream().use { out ->
            fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.write("RIFF".toByteArray()); int(36 + data.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
            out.write("data".toByteArray()); int(data.size); out.write(data)
        }
    }

    /** Читает WAV (PCM 16/32 бит, float, любое число каналов) → моно float и частота. */
    fun read(file: File): Pair<FloatArray, Int> {
        val b = file.readBytes()
        fun i32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        fun i16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt()) shl 8)
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") throw IOException("Это не WAV-файл")
        var pos = 12
        var format = 1; var channels = 1; var rate = 16000; var bits = 16
        var dataStart = -1; var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val len = i32(pos + 4)
            if (id == "fmt ") {
                format = i16(pos + 8) and 0xFFFF
                channels = i16(pos + 10) and 0xFFFF
                rate = i32(pos + 12)
                bits = i16(pos + 22) and 0xFFFF
                if (format == 0xFFFE) format = i16(pos + 32) and 0xFFFF // WAVE_FORMAT_EXTENSIBLE
            } else if (id == "data") {
                dataStart = pos + 8
                dataLen = minOf(len, b.size - dataStart)
                break
            }
            pos += 8 + len + (len and 1)
        }
        if (dataStart < 0) throw IOException("В WAV нет звука")
        val bytesPer = bits / 8
        val frames = dataLen / (bytesPer * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val o = dataStart + (f * channels + c) * bytesPer
                sum += when {
                    format == 3 && bits == 32 -> java.lang.Float.intBitsToFloat(i32(o))
                    bits == 16 -> i16(o).toShort() / 32768f
                    bits == 32 -> i32(o) / 2147483648f
                    bits == 8 -> ((b[o].toInt() and 0xFF) - 128) / 128f
                    bits == 24 -> (((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() shl 16)) / 8388608f)
                    else -> 0f
                }
            }
            out[f] = sum / channels
        }
        return out to rate
    }
}
