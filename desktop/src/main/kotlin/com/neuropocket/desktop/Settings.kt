package com.neuropocket.desktop

import org.json.JSONObject
import java.io.File

/** Папки приложения: всё хранится в «Документы/NeuroPocket» (можно переопределить NEUROPOCKET_HOME). */
object Paths {
    val root: File by lazy {
        val env = System.getenv("NEUROPOCKET_HOME")
        val home = System.getProperty("user.home")
        val docs = File(home, "Documents").takeIf { it.isDirectory } ?: File(home)
        (if (env.isNullOrBlank()) File(docs, "NeuroPocket") else File(env)).also { it.mkdirs() }
    }
    val chatModels get() = File(root, "models/chat").also { it.mkdirs() }
    val imageModels get() = File(root, "models/image").also { it.mkdirs() }
    val speechModels get() = File(root, "models/speech").also { it.mkdirs() }
    val outputs get() = File(root, "outputs").also { it.mkdirs() }
    val settingsFile get() = File(root, "settings.json")
    val profilesFile get() = File(imageModels, "profiles.json")
}

data class DesktopSettings(
    // ----- Чат -----
    val systemPrompt: String = "Ты полезный ассистент. Отвечай на языке пользователя.",
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 2048,
    val contextSize: Int = 8192,
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 16),
    val chatModel: String = "",
    val visionMaxSide: Int = 1024,
    val videoFrames: Int = 8,
    // ----- Видеокарта -----
    val useGpu: Boolean = true,
    /** Сколько слоёв чат-модели отдавать видеокарте (999 — все). */
    val gpuLayers: Int = 999,
    /** Держать веса картинок/видео в оперативной памяти (для видеокарт с малой памятью). */
    val sdOffloadToCpu: Boolean = false,
    /** Обработка изображения по частям — меньше видеопамяти. */
    val sdVaeTiling: Boolean = true,
    val sdWeightType: String = "",
    val sdMaxVram: String = "",
    // ----- Картинки и видео -----
    val imageProfile: String = "",
    val videoProfile: String = "",
    // ----- Речь -----
    val asrModelDir: String = "",
    val ttsModelDir: String = "",
    val ttsSpeed: Float = 1f,
    val autoSpeak: Boolean = false,
    // ----- Hugging Face -----
    val hfToken: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("systemPrompt", systemPrompt); put("temperature", temperature.toDouble()); put("topP", topP.toDouble())
        put("topK", topK); put("repeatPenalty", repeatPenalty.toDouble()); put("maxTokens", maxTokens)
        put("contextSize", contextSize); put("threads", threads); put("chatModel", chatModel)
        put("visionMaxSide", visionMaxSide); put("videoFrames", videoFrames)
        put("useGpu", useGpu); put("gpuLayers", gpuLayers); put("sdOffloadToCpu", sdOffloadToCpu)
        put("sdVaeTiling", sdVaeTiling); put("sdWeightType", sdWeightType); put("sdMaxVram", sdMaxVram)
        put("imageProfile", imageProfile); put("videoProfile", videoProfile)
        put("asrModelDir", asrModelDir); put("ttsModelDir", ttsModelDir); put("ttsSpeed", ttsSpeed.toDouble())
        put("autoSpeak", autoSpeak); put("hfToken", hfToken)
    }

    companion object {
        fun load(): DesktopSettings {
            val d = DesktopSettings()
            val f = Paths.settingsFile
            if (!f.exists()) return d
            return try {
                val o = JSONObject(f.readText())
                DesktopSettings(
                    systemPrompt = o.optString("systemPrompt", d.systemPrompt),
                    temperature = o.optDouble("temperature", d.temperature.toDouble()).toFloat(),
                    topP = o.optDouble("topP", d.topP.toDouble()).toFloat(),
                    topK = o.optInt("topK", d.topK),
                    repeatPenalty = o.optDouble("repeatPenalty", d.repeatPenalty.toDouble()).toFloat(),
                    maxTokens = o.optInt("maxTokens", d.maxTokens),
                    contextSize = o.optInt("contextSize", d.contextSize),
                    threads = o.optInt("threads", d.threads),
                    chatModel = o.optString("chatModel", ""),
                    visionMaxSide = o.optInt("visionMaxSide", d.visionMaxSide),
                    videoFrames = o.optInt("videoFrames", d.videoFrames),
                    useGpu = o.optBoolean("useGpu", d.useGpu),
                    gpuLayers = o.optInt("gpuLayers", d.gpuLayers),
                    sdOffloadToCpu = o.optBoolean("sdOffloadToCpu", d.sdOffloadToCpu),
                    sdVaeTiling = o.optBoolean("sdVaeTiling", d.sdVaeTiling),
                    sdWeightType = o.optString("sdWeightType", ""),
                    sdMaxVram = o.optString("sdMaxVram", ""),
                    imageProfile = o.optString("imageProfile", ""),
                    videoProfile = o.optString("videoProfile", ""),
                    asrModelDir = o.optString("asrModelDir", ""),
                    ttsModelDir = o.optString("ttsModelDir", ""),
                    ttsSpeed = o.optDouble("ttsSpeed", 1.0).toFloat(),
                    autoSpeak = o.optBoolean("autoSpeak", false),
                    hfToken = o.optString("hfToken", ""),
                )
            } catch (e: Exception) {
                d
            }
        }
    }

    fun save() {
        Paths.settingsFile.writeText(toJson().toString(2))
    }
}
