package com.neuropocket.app.engine

import androidx.annotation.Keep

/** Колбэк потоковой генерации: получает кусочки текста в UTF-8. */
@Keep
fun interface TokenCallback {
    /** Вернуть false, чтобы остановить генерацию. */
    fun onToken(bytes: ByteArray): Boolean
}

/** Прямые вызовы в libneuropocket.so (см. app/src/main/cpp/llm_jni.cpp). */
@Keep
internal object LlamaNative {
    const val RES_TEMPLATE_ERROR = -1
    const val RES_CONTEXT_FULL = -2
    const val RES_DECODE_ERROR = -3
    const val RES_BAD_HANDLE = -4
    const val RES_MEDIA_ERROR = -5

    init {
        System.loadLibrary("neuropocket")
    }

    external fun init(nativeLibDir: String)
    external fun systemInfo(): String
    external fun mediaMarker(): String
    external fun load(path: String, mmprojPath: String?, nCtx: Int, nThreads: Int): Long
    external fun hasVision(handle: Long): Boolean
    external fun free(handle: Long)
    external fun modelInfo(handle: Long): String
    external fun resetChat(handle: Long)
    external fun stop()
    external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        images: Array<ByteArray>,
        widths: IntArray,
        heights: IntArray,
        imageIds: Array<String>,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        maxTokens: Int,
        callback: TokenCallback,
    ): Int
}
