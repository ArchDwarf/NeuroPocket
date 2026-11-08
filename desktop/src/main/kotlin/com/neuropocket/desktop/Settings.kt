package com.neuropocket.desktop

import org.json.JSONArray
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
    /** Режим чата: assistant, coder, translator, custom (свой системный промпт). */
    val chatPreset: String = "assistant",
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
    // ----- Где считать (устройства) -----
    /** Чат: auto — все видеокарты; cpu — процессор; gpu — выбранные видеокарты; hybrid — видеокарты + процессор. */
    val chatDevice: String = "auto",
    /** Выбранные видеокарты для чата (пусто — все). */
    val chatGpus: List<String> = emptyList(),
    /** Деление между видеокартами: layer — по слоям, row — по строкам (параллельно). */
    val chatSplit: String = "layer",
    /** Доли модели по видеокартам (в порядке chatGpus); пусто — по свободной памяти. */
    val chatTensorSplit: List<Float> = emptyList(),
    /** Картинки/видео: auto, cpu, gpu (выбранные видеокарты). */
    val sdDevice: String = "auto",
    val sdGpus: List<String> = emptyList(),
    /** Где текстовые энкодеры: same — там же, cpu — процессор, или имя устройства. */
    val sdTextEncoder: String = "same",
    /** Где VAE: same, cpu или имя устройства. */
    val sdVae: String = "same",
    val sdSplit: String = "layer",
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
        put("systemPrompt", systemPrompt); put("chatPreset", chatPreset); put("temperature", temperature.toDouble()); put("topP", topP.toDouble())
        put("topK", topK); put("repeatPenalty", repeatPenalty.toDouble()); put("maxTokens", maxTokens)
        put("contextSize", contextSize); put("threads", threads); put("chatModel", chatModel)
        put("visionMaxSide", visionMaxSide); put("videoFrames", videoFrames)
        put("useGpu", useGpu); put("gpuLayers", gpuLayers); put("sdOffloadToCpu", sdOffloadToCpu)
        put("sdVaeTiling", sdVaeTiling); put("sdWeightType", sdWeightType); put("sdMaxVram", sdMaxVram)
        put("chatDevice", chatDevice); put("chatGpus", JSONArray(chatGpus)); put("chatSplit", chatSplit)
        put("chatTensorSplit", JSONArray(chatTensorSplit.map { it.toDouble() }))
        put("sdDevice", sdDevice); put("sdGpus", JSONArray(sdGpus)); put("sdTextEncoder", sdTextEncoder)
        put("sdVae", sdVae); put("sdSplit", sdSplit)
        put("imageProfile", imageProfile); put("videoProfile", videoProfile)
        put("asrModelDir", asrModelDir); put("ttsModelDir", ttsModelDir); put("ttsSpeed", ttsSpeed.toDouble())
        put("autoSpeak", autoSpeak); put("hfToken", hfToken)
    }

    companion object {
        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

        fun load(): DesktopSettings {
            val d = DesktopSettings()
            val f = Paths.settingsFile
            if (!f.exists()) return d
            return try {
                val o = JSONObject(f.readText())
                DesktopSettings(
                    systemPrompt = o.optString("systemPrompt", d.systemPrompt),
                    chatPreset = o.optString("chatPreset", d.chatPreset),
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
                    chatDevice = o.optString("chatDevice", if (o.optBoolean("useGpu", true)) "auto" else "cpu"),
                    chatGpus = strings(o.optJSONArray("chatGpus")),
                    chatSplit = o.optString("chatSplit", "layer"),
                    chatTensorSplit = o.optJSONArray("chatTensorSplit")?.let { a -> List(a.length()) { a.getDouble(it).toFloat() } } ?: emptyList(),
                    sdDevice = o.optString("sdDevice", if (o.optBoolean("useGpu", true)) "auto" else "cpu"),
                    sdGpus = strings(o.optJSONArray("sdGpus")),
                    sdTextEncoder = o.optString("sdTextEncoder", "same"),
                    sdVae = o.optString("sdVae", "same"),
                    sdSplit = o.optString("sdSplit", "layer"),
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

/** Режимы единого чата. */
object ChatPresets {
    data class Preset(val id: String, val title: String, val icon: String, val prompt: String)

    private const val CAPS =
        "Ты работаешь внутри приложения NeuroPocket полностью на компьютере пользователя, без интернета. " +
            "Приложение само рисует картинки, изменяет фото и создаёт видео, когда пользователь просит " +
            "(«нарисуй…», «сделай видео…», «оживи это фото») — тебе не нужно описывать, как это сделать, " +
            "и не нужно говорить, что ты не умеешь рисовать. Пользователь может прислать фото, видео, " +
            "файлы с кодом, документы и голосовые записи — их содержимое приходит в сообщении."

    val all = listOf(
        Preset(
            "assistant", "Ассистент", "💬",
            "Ты полезный и дружелюбный ассистент. Отвечай на языке пользователя, по делу, " +
                "используй списки и примеры, когда это помогает. $CAPS",
        ),
        Preset(
            "coder", "Программист", "👨‍💻",
            "Ты опытный программист. Пиши рабочий, полный код без заглушек, всегда в блоках ``` с указанием языка " +
                "(```python, ```kotlin…). Кратко объясняй решение и важные детали; при исправлении ошибки " +
                "укажи причину. Если в сообщении приложен файл — опирайся на его содержимое. " +
                "Отвечай на языке пользователя. $CAPS",
        ),
        Preset(
            "translator", "Переводчик", "🌐",
            "Ты профессиональный переводчик. Если текст на русском — переведи на английский, иначе — на русский " +
                "(если пользователь не указал другой язык). Сохраняй смысл, стиль и форматирование. " +
                "Выводи только перевод, без пояснений.",
        ),
        Preset("custom", "Свой", "⚙️", ""),
    )

    fun get(id: String) = all.find { it.id == id } ?: all.first()

    fun systemPrompt(s: DesktopSettings): String =
        if (s.chatPreset == "custom") s.systemPrompt else get(s.chatPreset).prompt
}
