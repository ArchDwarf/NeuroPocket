package com.neuropocket.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jcodec.api.FrameGrab
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.model.Rational
import org.jcodec.scale.AWTUtil
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.max
import kotlin.math.roundToInt

/** Кадр для модели: RGB-байты и подпись (время в видео). */
class DFrame(val rgb: ByteArray, val width: Int, val height: Int, val label: String = "")

enum class DAttachmentKind { IMAGE, VIDEO, FILE, AUDIO }

/**
 * Вложение в чате: фото, видео (как набор кадров), текстовый файл/документ
 * (его содержимое в [text]) или аудио (расшифровка в [text]).
 */
class DAttachment(
    val id: String,
    val kind: DAttachmentKind,
    val name: String,
    val thumbnail: ImageBitmap,
    val frames: List<DFrame>,
    val durationSec: Float = 0f,
    val text: String = "",
    /** Для аудио: звук до расшифровки. */
    val audio: Pair<FloatArray, Int>? = null,
)

object DesktopMedia {
    val IMAGE_EXT = listOf("png", "jpg", "jpeg", "bmp", "gif", "webp")
    val VIDEO_EXT = listOf("mp4", "mov", "m4v", "3gp")
    val AUDIO_EXT = listOf("wav")
    val TEXT_EXT = listOf(
        "txt", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "html", "htm", "css",
        "py", "kt", "kts", "java", "js", "jsx", "ts", "tsx", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift", "rb",
        "php", "lua", "dart", "scala", "sql", "sh", "bat", "cmd", "ps1", "gradle", "r", "m", "vue", "svelte",
    )
    val DOC_EXT = listOf("pdf")
    /** Сколько символов файла передавать модели. */
    const val MAX_FILE_CHARS = 60_000

    /** Значок для файла: прямоугольник с расширением. */
    fun fileIcon(ext: String, color: java.awt.Color): ImageBitmap {
        val img = BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = color
        g.fillRoundRect(0, 0, 160, 160, 28, 28)
        g.color = java.awt.Color.WHITE
        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, if (ext.length > 4) 30 else 40)
        val label = ext.uppercase().take(6)
        val fm = g.fontMetrics
        g.drawString(label, (160 - fm.stringWidth(label)) / 2, 80 + fm.ascent / 2 - 4)
        g.dispose()
        return img.toComposeImageBitmap()
    }

    // ---------- Диалоги ----------

    private var lastDir: File? = null

    fun chooseFile(title: String, exts: List<String>, description: String): File? {
        val fc = JFileChooser(lastDir ?: File(System.getProperty("user.home")))
        fc.dialogTitle = title
        if (exts.isNotEmpty()) fc.fileFilter = FileNameExtensionFilter(description, *exts.toTypedArray())
        return if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            lastDir = fc.selectedFile.parentFile
            fc.selectedFile
        } else null
    }

    fun chooseFiles(title: String, exts: List<String>, description: String): List<File> {
        val fc = JFileChooser(lastDir ?: File(System.getProperty("user.home")))
        fc.dialogTitle = title
        fc.isMultiSelectionEnabled = true
        if (exts.isNotEmpty()) fc.fileFilter = FileNameExtensionFilter(description, *exts.toTypedArray())
        return if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            lastDir = fc.selectedFiles.firstOrNull()?.parentFile
            fc.selectedFiles.toList()
        } else emptyList()
    }

    fun chooseSave(title: String, defaultName: String): File? {
        val fc = JFileChooser(lastDir ?: Paths.outputs)
        fc.dialogTitle = title
        fc.selectedFile = File(fc.currentDirectory, defaultName)
        return if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
    }

    fun openFolder(dir: File) {
        try {
            java.awt.Desktop.getDesktop().open(dir)
        } catch (_: Exception) {
        }
    }

    // ---------- Картинки ----------

    fun toRgb(img: BufferedImage): ByteArray {
        val w = img.width
        val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        val out = ByteArray(w * h * 3)
        for (i in px.indices) {
            val c = px[i]
            out[i * 3] = (c shr 16 and 0xFF).toByte()
            out[i * 3 + 1] = (c shr 8 and 0xFF).toByte()
            out[i * 3 + 2] = (c and 0xFF).toByte()
        }
        return out
    }

    fun fromRgb(rgb: ByteArray, w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val px = IntArray(w * h) { i ->
            ((rgb[i * 3].toInt() and 0xFF) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[i * 3 + 2].toInt() and 0xFF)
        }
        img.setRGB(0, 0, w, h, px, 0, w)
        return img
    }

    fun toGray(img: BufferedImage): ByteArray {
        val w = img.width
        val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        return ByteArray(w * h) { i -> if ((px[i] and 0xFF) > 127) 255.toByte() else 0 }
    }

    fun resize(img: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    fun scaleToMax(img: BufferedImage, maxSide: Int): BufferedImage {
        val s = maxSide.toFloat() / max(img.width, img.height)
        if (s >= 1f) return resize(img, img.width, img.height)
        return resize(img, (img.width * s).roundToInt().coerceAtLeast(1), (img.height * s).roundToInt().coerceAtLeast(1))
    }

    /** Вписывает в рамку и делает стороны кратными 64 (требование диффузионных моделей). */
    fun fitForDiffusion(img: BufferedImage, maxSide: Int, multiple: Int = 64): BufferedImage {
        val s = maxSide.toFloat() / max(img.width, img.height)
        val w = ((img.width * s / multiple).roundToInt() * multiple).coerceIn(multiple, maxSide)
        val h = ((img.height * s / multiple).roundToInt() * multiple).coerceIn(multiple, maxSide)
        return resize(img, w, h)
    }

    suspend fun loadImage(file: File): BufferedImage = withContext(Dispatchers.IO) {
        ImageIO.read(file) ?: throw IOException("Неподдерживаемый формат изображения: ${file.name}")
    }

    fun savePng(img: BufferedImage, file: File) {
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
    }

    // ---------- Видео ----------

    /** Текстовый файл или PDF → текст для модели. */
    private suspend fun loadTextFile(file: File): DAttachment = withContext(Dispatchers.IO) {
        val ext = file.extension.lowercase()
        val text = if (ext == "pdf") {
            org.apache.pdfbox.Loader.loadPDF(file).use { doc ->
                org.apache.pdfbox.text.PDFTextStripper().getText(doc)
            }
        } else {
            val bytes = file.readBytes()
            if (bytes.take(8000).count { it == 0.toByte() } > 0) throw IOException("${file.name}: это не текстовый файл")
            String(bytes, Charsets.UTF_8)
        }
        if (text.isBlank()) throw IOException("${file.name}: в файле нет текста (для сканов PDF нужно распознавание)")
        val color = if (ext == "pdf") java.awt.Color(0xC0392B) else java.awt.Color(0x2E86C1)
        DAttachment(
            UUID.randomUUID().toString(), DAttachmentKind.FILE, file.name, fileIcon(ext, color), emptyList(),
            text = if (text.length > MAX_FILE_CHARS) text.take(MAX_FILE_CHARS) + "\n…[файл обрезан]" else text,
        )
    }

    private fun timeLabel(sec: Double): String {
        val t = sec.roundToInt()
        return String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
    }

    /** Кадр из видео (H.264 MP4). */
    suspend fun videoFrame(file: File, second: Double): BufferedImage = withContext(Dispatchers.IO) {
        NIOUtils.readableChannel(file).use { ch ->
            val grab = FrameGrab.createFrameGrab(ch)
            grab.seekToSecondPrecise(second)
            val pic = grab.nativeFrame ?: throw IOException("Не удалось прочитать кадр")
            AWTUtil.toBufferedImage(pic)
        }
    }

    suspend fun videoDuration(file: File): Double = withContext(Dispatchers.IO) {
        NIOUtils.readableChannel(file).use { ch ->
            FrameGrab.createFrameGrab(ch).videoTrack.meta.totalDuration
        }
    }

    suspend fun loadAttachment(file: File, maxSide: Int, maxFrames: Int): DAttachment {
        val ext = file.extension.lowercase()
        return if (ext in VIDEO_EXT) loadVideo(file, maxSide, maxFrames)
        else if (ext in TEXT_EXT || ext in DOC_EXT) loadTextFile(file)
        else if (ext in AUDIO_EXT) withContext(Dispatchers.IO) {
            val audio = com.neuropocket.speech.Wav.read(file)
            DAttachment(
                UUID.randomUUID().toString(), DAttachmentKind.AUDIO, file.name, fileIcon("wav", java.awt.Color(0x8E44AD)),
                emptyList(), durationSec = audio.first.size.toFloat() / audio.second, audio = audio,
            )
        }
        else {
            val img = scaleToMax(loadImage(file), maxSide)
            DAttachment(
                UUID.randomUUID().toString(), DAttachmentKind.IMAGE, file.name,
                scaleToMax(img, 256).toComposeImageBitmap(), listOf(DFrame(toRgb(img), img.width, img.height)),
            )
        }
    }

    private suspend fun loadVideo(file: File, maxSide: Int, maxFrames: Int): DAttachment = withContext(Dispatchers.IO) {
        try {
            NIOUtils.readableChannel(file).use { ch ->
                val grab = FrameGrab.createFrameGrab(ch)
                val duration = grab.videoTrack.meta.totalDuration
                val count = duration.toInt().coerceIn(1, maxFrames.coerceAtLeast(1))
                val frames = mutableListOf<DFrame>()
                var thumb: ImageBitmap? = null
                for (i in 0 until count) {
                    val t = if (count == 1) duration / 2 else duration * (2 * i + 1) / (2.0 * count)
                    grab.seekToSecondPrecise(t)
                    val pic = grab.nativeFrame ?: continue
                    val img = scaleToMax(AWTUtil.toBufferedImage(pic), maxSide)
                    if (thumb == null) thumb = scaleToMax(img, 256).toComposeImageBitmap()
                    frames += DFrame(toRgb(img), img.width, img.height, timeLabel(t))
                }
                if (frames.isEmpty()) throw IOException("Не удалось прочитать кадры")
                DAttachment(UUID.randomUUID().toString(), DAttachmentKind.VIDEO, file.name, thumb!!, frames, duration.toFloat())
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Видео не поддерживается (нужен MP4 с кодеком H.264): ${e.message}")
        }
    }

    /** Собирает MP4 (H.264) из кадров RGB. */
    suspend fun encodeMp4(frames: List<ByteArray>, w: Int, h: Int, fps: Int, out: File, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            out.parentFile?.mkdirs()
            // H.264 требует чётных размеров
            val ew = w and 1.inv()
            val eh = h and 1.inv()
            val ch = NIOUtils.writableFileChannel(out.absolutePath)
            try {
                val enc = AWTSequenceEncoder(ch, Rational.R(fps, 1))
                frames.forEachIndexed { i, f ->
                    var img = fromRgb(f, w, h)
                    if (ew != w || eh != h) img = resize(img, ew, eh)
                    enc.encodeImage(img)
                    onProgress((i + 1f) / frames.size)
                }
                enc.finish()
            } finally {
                NIOUtils.closeQuietly(ch)
            }
        }
}
