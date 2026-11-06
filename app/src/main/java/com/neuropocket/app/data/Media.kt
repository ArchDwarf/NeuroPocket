package com.neuropocket.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Кадр для модели: RGB-байты (width*height*3) и подпись (время в видео). */
class Frame(val rgb: ByteArray, val width: Int, val height: Int, val label: String = "")

enum class AttachmentKind { IMAGE, VIDEO }

/**
 * Вложение в сообщении: фото или видео (как набор кадров).
 * [id] постоянный — по нему движок понимает, что картинка уже обработана.
 */
class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val thumbnail: Bitmap,
    val frames: List<Frame>,
    val durationSec: Float = 0f,
)

object Media {

    /** Bitmap → RGB-байты. */
    fun toRgb(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3)
        for (i in px.indices) {
            val c = px[i]
            out[i * 3] = (c shr 16 and 0xFF).toByte()
            out[i * 3 + 1] = (c shr 8 and 0xFF).toByte()
            out[i * 3 + 2] = (c and 0xFF).toByte()
        }
        return out
    }

    /** RGB-байты → Bitmap. */
    fun fromRgb(rgb: ByteArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        for (i in px.indices) {
            px[i] = (0xFF shl 24) or
                ((rgb[i * 3].toInt() and 0xFF) shl 16) or
                ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or
                (rgb[i * 3 + 2].toInt() and 0xFF)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Маска (белое — перерисовать) → байты оттенков серого. */
    fun toGray(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return ByteArray(w * h) { i -> if ((px[i] ushr 24) > 0 && (px[i] and 0xFF) > 127) 255.toByte() else 0 }
    }

    private fun scaleToMax(bmp: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (s >= 1f) return bmp.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1),
            (bmp.height * s).roundToInt().coerceAtLeast(1), true)
    }

    /** Загружает фото с учётом поворота, уменьшая до maxSide по большей стороне. */
    suspend fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = maxSide.toFloat() / max(info.size.width, info.size.height)
            if (s < 1f) {
                decoder.setTargetSize(
                    (info.size.width * s).roundToInt().coerceAtLeast(1),
                    (info.size.height * s).roundToInt().coerceAtLeast(1),
                )
            }
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun isVideo(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("video/") == true

    /** Превращает выбранный файл во вложение для модели. */
    suspend fun loadAttachment(context: Context, uri: Uri, maxSide: Int, maxFrames: Int): Attachment =
        if (isVideo(context, uri)) loadVideo(context, uri, maxSide, maxFrames) else {
            val bmp = loadBitmap(context, uri, maxSide)
            Attachment(
                id = UUID.randomUUID().toString(),
                kind = AttachmentKind.IMAGE,
                thumbnail = scaleToMax(bmp, 256),
                frames = listOf(Frame(toRgb(bmp), bmp.width, bmp.height)),
            )
        }

    /** Вложение из готовой картинки (например, нарисованной в чате). */
    fun attachmentFromBitmap(bmp: Bitmap, maxSide: Int): Attachment {
        val img = scaleToMax(bmp, maxSide)
        return Attachment(
            id = UUID.randomUUID().toString(),
            kind = AttachmentKind.IMAGE,
            thumbnail = scaleToMax(img, 256),
            frames = listOf(Frame(toRgb(img), img.width, img.height)),
        )
    }

    private fun timeLabel(sec: Float): String {
        val t = sec.roundToInt()
        return String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
    }

    /** Видео → равномерно взятые кадры (модель смотрит их как серию снимков). */
    private suspend fun loadVideo(context: Context, uri: Uri, maxSide: Int, maxFrames: Int): Attachment =
        withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxSide
                val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxSide
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val (sw, sh) = if (rot == 90 || rot == 270) vh to vw else vw to vh
                val s = minOf(1f, maxSide.toFloat() / max(sw, sh))
                val tw = (sw * s).roundToInt().coerceAtLeast(16)
                val th = (sh * s).roundToInt().coerceAtLeast(16)

                // Около 1 кадра в секунду, но не больше maxFrames
                val count = (durationMs / 1000L).toInt().coerceIn(1, maxFrames.coerceAtLeast(1))
                val frames = mutableListOf<Frame>()
                var thumb: Bitmap? = null
                for (i in 0 until count) {
                    val tMs = if (count == 1) durationMs / 2 else durationMs * (2 * i + 1) / (2L * count)
                    val bmp = r.getScaledFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
                        ?: continue
                    val argb = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    if (thumb == null) thumb = scaleToMax(argb, 256)
                    frames += Frame(toRgb(argb), argb.width, argb.height, timeLabel(tMs / 1000f))
                }
                if (frames.isEmpty()) throw IOException("Не удалось прочитать кадры видео")
                Attachment(
                    id = UUID.randomUUID().toString(),
                    kind = AttachmentKind.VIDEO,
                    thumbnail = thumb!!,
                    frames = frames,
                    durationSec = durationMs / 1000f,
                )
            } finally {
                r.release()
            }
        }

    /**
     * Готовит исходник для генерации: вписывает в рамку maxSide и
     * делает стороны кратными 64 (требование моделей Stable Diffusion).
     */
    fun fitForDiffusion(bmp: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(bmp.width, bmp.height)
        val w = ((bmp.width * s / 64f).roundToInt() * 64).coerceIn(64, maxSide)
        val h = ((bmp.height * s / 64f).roundToInt() * 64).coerceIn(64, maxSide)
        return resizeExact(bmp, w, h)
    }

    fun resizeExact(bmp: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Сохраняет картинку в галерею (Pictures/NeuroPocket). */
    suspend fun saveToGallery(context: Context, bmp: Bitmap): Uri? = withContext(Dispatchers.IO) {
        val name = "NeuroPocket_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NeuroPocket")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NeuroPocket")
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Uri.fromFile(f)
        }
    }
}
