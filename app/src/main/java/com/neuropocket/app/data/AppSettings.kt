package com.neuropocket.app.data

import android.content.Context
import androidx.core.content.edit

data class Settings(
    val systemPrompt: String = "Ты полезный ассистент. Отвечай на языке пользователя.",
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val contextSize: Int = 4096,
    val threads: Int = defaultThreads(),
    val hfToken: String = "",
    val activeModelPath: String = "",
    /** До какого размера (по большей стороне) уменьшать фото для модели. */
    val visionMaxSide: Int = 768,
    /** Сколько кадров брать из видео. */
    val videoFrames: Int = 6,
    /** Активная модель для картинок. */
    val imageModelPath: String = "",
    /** Тип весов при загрузке модели картинок: "" — как в файле, иначе f16 / q8_0 / q4_0. */
    val imageWeightType: String = "",
    /** Папка модели распознавания речи. */
    val asrModelDir: String = "",
    /** Папка модели синтеза речи. */
    val ttsModelDir: String = "",
    val ttsSpeed: Float = 1f,
    /** Озвучивать ответы автоматически. */
    val autoSpeak: Boolean = false,
) {
    companion object {
        fun defaultThreads(): Int =
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}

/** Настройки приложения в SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            systemPrompt = prefs.getString("systemPrompt", d.systemPrompt) ?: d.systemPrompt,
            temperature = prefs.getFloat("temperature", d.temperature),
            topP = prefs.getFloat("topP", d.topP),
            topK = prefs.getInt("topK", d.topK),
            repeatPenalty = prefs.getFloat("repeatPenalty", d.repeatPenalty),
            maxTokens = prefs.getInt("maxTokens", d.maxTokens),
            contextSize = prefs.getInt("contextSize", d.contextSize),
            threads = prefs.getInt("threads", d.threads),
            hfToken = prefs.getString("hfToken", "") ?: "",
            activeModelPath = prefs.getString("activeModelPath", "") ?: "",
            visionMaxSide = prefs.getInt("visionMaxSide", d.visionMaxSide),
            videoFrames = prefs.getInt("videoFrames", d.videoFrames),
            imageModelPath = prefs.getString("imageModelPath", "") ?: "",
            imageWeightType = prefs.getString("imageWeightType", "") ?: "",
            asrModelDir = prefs.getString("asrModelDir", "") ?: "",
            ttsModelDir = prefs.getString("ttsModelDir", "") ?: "",
            ttsSpeed = prefs.getFloat("ttsSpeed", d.ttsSpeed),
            autoSpeak = prefs.getBoolean("autoSpeak", d.autoSpeak),
        )
    }

    fun save(s: Settings) = prefs.edit {
        putString("systemPrompt", s.systemPrompt)
        putFloat("temperature", s.temperature)
        putFloat("topP", s.topP)
        putInt("topK", s.topK)
        putFloat("repeatPenalty", s.repeatPenalty)
        putInt("maxTokens", s.maxTokens)
        putInt("contextSize", s.contextSize)
        putInt("threads", s.threads)
        putString("hfToken", s.hfToken)
        putString("activeModelPath", s.activeModelPath)
        putInt("visionMaxSide", s.visionMaxSide)
        putInt("videoFrames", s.videoFrames)
        putString("imageModelPath", s.imageModelPath)
        putString("imageWeightType", s.imageWeightType)
        putString("asrModelDir", s.asrModelDir)
        putString("ttsModelDir", s.ttsModelDir)
        putFloat("ttsSpeed", s.ttsSpeed)
        putBoolean("autoSpeak", s.autoSpeak)
    }
}
