package com.neuropocket.desktop

import com.neuropocket.sd.SdModelFiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * Профиль модели картинок или видео: набор файлов (для больших моделей — несколько
 * компонентов) и подходящие ей настройки по умолчанию.
 */
data class SdProfile(
    val name: String,
    val video: Boolean,
    val files: SdModelFiles,
    val steps: Int = 20,
    val cfg: Float = 7f,
    val sampler: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val frames: Int = 33,
    val fps: Int = 16,
    val flowShift: Float = 0f,
    val highNoiseSteps: Int = 0,
    val negative: String = "",
    /** Модель умеет редактировать по инструкции (ref-картинка). */
    val canEdit: Boolean = false,
    val note: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("video", video); put("steps", steps); put("cfg", cfg.toDouble())
        put("sampler", sampler); put("width", width); put("height", height); put("frames", frames); put("fps", fps)
        put("flowShift", flowShift.toDouble()); put("highNoiseSteps", highNoiseSteps); put("negative", negative)
        put("canEdit", canEdit); put("note", note)
        val f = JSONObject()
        files.toMap().forEach { (k, v) -> if (!v.isNullOrBlank()) f.put(k, v) }
        put("files", f)
    }

    companion object {
        fun fromJson(o: JSONObject): SdProfile {
            val f = o.optJSONObject("files") ?: JSONObject()
            fun s(k: String) = f.optString(k, "").ifBlank { null }
            return SdProfile(
                name = o.getString("name"),
                video = o.optBoolean("video"),
                files = SdModelFiles(
                    model = s("model"), diffusionModel = s("diffusion_model"),
                    highNoiseDiffusionModel = s("high_noise_diffusion_model"), vae = s("vae"), taesd = s("taesd"),
                    clipL = s("clip_l"), clipG = s("clip_g"), clipVision = s("clip_vision"), t5xxl = s("t5xxl"),
                    llm = s("llm"), llmVision = s("llm_vision"),
                ),
                steps = o.optInt("steps", 20), cfg = o.optDouble("cfg", 7.0).toFloat(), sampler = o.optString("sampler", ""),
                width = o.optInt("width", 512), height = o.optInt("height", 512), frames = o.optInt("frames", 33),
                fps = o.optInt("fps", 16), flowShift = o.optDouble("flowShift", 0.0).toFloat(),
                highNoiseSteps = o.optInt("highNoiseSteps", 0), negative = o.optString("negative", ""),
                canEdit = o.optBoolean("canEdit", false), note = o.optString("note", ""),
            )
        }

        fun loadAll(): List<SdProfile> = try {
            val f = Paths.profilesFile
            if (!f.exists()) emptyList() else {
                val arr = JSONArray(f.readText())
                List(arr.length()) { fromJson(arr.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            emptyList()
        }

        fun saveAll(list: List<SdProfile>) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            Paths.profilesFile.writeText(arr.toString(2))
        }
    }
}

/** Компонент набора: какой файл взять из какого репозитория (по шаблонам, первый подошедший). */
data class PresetFile(val role: String, val repo: String, val patterns: List<String>)

/** Готовый набор для скачивания одной кнопкой. */
data class Preset(
    val id: String,
    val title: String,
    val note: String,
    val video: Boolean,
    val vramGb: Int,
    val files: List<PresetFile>,
    val profile: SdProfile,
)

private const val VIDEO_NEGATIVE =
    "static, blurry, low quality, worst quality, jpeg artifacts, deformed, distorted, disfigured, watermark, text, subtitles"

private fun p(role: String, repo: String, vararg patterns: String) = PresetFile(role, repo, patterns.toList())
private fun prof(name: String, video: Boolean = false) = SdProfile(name, video, SdModelFiles())

val PRESETS = listOf(
    // ---------------- Картинки ----------------
    Preset(
        "z-image-turbo", "Z-Image Turbo", "фотореализм, отлично понимает описания; 8 шагов", false, 6,
        listOf(
            p("diffusion_model", "leejet/Z-Image-Turbo-GGUF", "(?i)q4_k.*\\.gguf$", "(?i)q8_0.*\\.gguf$", "(?i)\\.gguf$"),
            p("vae", "black-forest-labs/FLUX.1-schnell", "^ae\\.safetensors$"),
            p("llm", "unsloth/Qwen3-4B-Instruct-2507-GGUF", "(?i)Q4_K_M\\.gguf$"),
        ),
        prof("Z-Image Turbo").copy(steps = 8, cfg = 1f, width = 1024, height = 1024),
    ),
    Preset(
        "flux2-klein-4b", "FLUX.2 klein 4B", "генерация и редактирование по инструкции; 4 шага", false, 8,
        listOf(
            p("diffusion_model", "leejet/FLUX.2-klein-4B-GGUF", "(?i)q4_k.*\\.gguf$", "(?i)q8_0.*\\.gguf$", "(?i)\\.gguf$"),
            p("vae", "black-forest-labs/FLUX.2-small-decoder", "(?i)full_encoder_small_decoder\\.safetensors$", "(?i)\\.safetensors$"),
            p("llm", "unsloth/Qwen3-4B-GGUF", "(?i)Q4_K_M\\.gguf$"),
        ),
        prof("FLUX.2 klein 4B").copy(steps = 4, cfg = 1f, width = 1024, height = 1024, canEdit = true),
    ),
    Preset(
        "flux1-schnell", "FLUX.1 schnell", "высокое качество, 4 шага", false, 10,
        listOf(
            p("diffusion_model", "city96/FLUX.1-schnell-gguf", "(?i)Q4_K_S\\.gguf$", "(?i)q4.*\\.gguf$", "(?i)\\.gguf$"),
            p("vae", "black-forest-labs/FLUX.1-schnell", "^ae\\.safetensors$"),
            p("clip_l", "comfyanonymous/flux_text_encoders", "^clip_l\\.safetensors$"),
            p("t5xxl", "city96/t5-v1_1-xxl-encoder-gguf", "(?i)Q5_K_M\\.gguf$", "(?i)Q8_0\\.gguf$", "(?i)\\.gguf$"),
        ),
        prof("FLUX.1 schnell").copy(steps = 4, cfg = 1f, sampler = "euler", width = 1024, height = 1024),
    ),
    Preset(
        "qwen-image", "Qwen-Image", "лучше всех пишет текст на картинках; тяжёлая", false, 16,
        listOf(
            p("diffusion_model", "QuantStack/Qwen-Image-GGUF", "(?i)Q4_K_S\\.gguf$", "(?i)Q4_0\\.gguf$", "(?i)q4.*\\.gguf$"),
            p("vae", "Comfy-Org/Qwen-Image_ComfyUI", "split_files/vae/qwen_image_vae\\.safetensors$"),
            p("llm", "mradermacher/Qwen2.5-VL-7B-Instruct-GGUF", "(?i)Q4_K_M\\.gguf$"),
        ),
        prof("Qwen-Image").copy(steps = 20, cfg = 2.5f, sampler = "euler", width = 1024, height = 1024),
    ),
    Preset(
        "sdxl", "Stable Diffusion XL", "классика 1024×1024, тысячи дообученных версий", false, 8,
        listOf(p("model", "stabilityai/stable-diffusion-xl-base-1.0", "^sd_xl_base_1\\.0\\.safetensors$")),
        prof("Stable Diffusion XL").copy(steps = 25, cfg = 6f, sampler = "euler_a", width = 1024, height = 1024,
            negative = "blurry, low quality, deformed, watermark, text"),
    ),
    Preset(
        "sd15", "Stable Diffusion 1.5", "лёгкая, работает даже без видеокарты", false, 4,
        listOf(p("model", "stable-diffusion-v1-5/stable-diffusion-v1-5", "^v1-5-pruned-emaonly\\.safetensors$")),
        prof("Stable Diffusion 1.5").copy(steps = 25, cfg = 7f, sampler = "euler_a", width = 512, height = 512,
            negative = "blurry, low quality, deformed, bad anatomy, watermark, text"),
    ),
    // ---------------- Видео ----------------
    Preset(
        "wan21-t2v-1.3b", "Wan 2.1 · 1.3B (текст → видео)", "самая лёгкая видеомодель, 480p", true, 8,
        listOf(
            p("diffusion_model", "Comfy-Org/Wan_2.1_ComfyUI_repackaged", "split_files/diffusion_models/wan2\\.1_t2v_1\\.3B_fp16\\.safetensors$"),
            p("vae", "Comfy-Org/Wan_2.1_ComfyUI_repackaged", "split_files/vae/wan_2\\.1_vae\\.safetensors$"),
            p("t5xxl", "city96/umt5-xxl-encoder-gguf", "(?i)Q8_0\\.gguf$", "(?i)Q5_K_M\\.gguf$", "(?i)\\.gguf$"),
        ),
        prof("Wan 2.1 1.3B", video = true).copy(steps = 20, cfg = 6f, sampler = "euler", width = 832, height = 480,
            frames = 33, fps = 16, negative = VIDEO_NEGATIVE),
    ),
    Preset(
        "wan22-ti2v-5b", "Wan 2.2 · 5B (текст/картинка → видео)", "лучше качество, умеет оживлять фото", true, 12,
        listOf(
            p("diffusion_model", "QuantStack/Wan2.2-TI2V-5B-GGUF", "(?i)Q4_K_M\\.gguf$", "(?i)Q8_0\\.gguf$", "(?i)\\.gguf$"),
            p("vae", "Comfy-Org/Wan_2.2_ComfyUI_Repackaged", "split_files/vae/wan2\\.2_vae\\.safetensors$"),
            p("t5xxl", "city96/umt5-xxl-encoder-gguf", "(?i)Q8_0\\.gguf$", "(?i)Q5_K_M\\.gguf$", "(?i)\\.gguf$"),
        ),
        prof("Wan 2.2 5B", video = true).copy(steps = 20, cfg = 5f, sampler = "euler", width = 832, height = 480,
            frames = 49, fps = 24, negative = VIDEO_NEGATIVE),
    ),
    Preset(
        "wan22-i2v-a14b", "Wan 2.2 · A14B (картинка → видео)", "максимальное качество; нужна мощная видеокарта", true, 20,
        listOf(
            p("diffusion_model", "QuantStack/Wan2.2-I2V-A14B-GGUF", "(?i)LowNoise.*Q4_K_M\\.gguf$", "(?i)low.*q4.*\\.gguf$"),
            p("high_noise_diffusion_model", "QuantStack/Wan2.2-I2V-A14B-GGUF", "(?i)HighNoise.*Q4_K_M\\.gguf$", "(?i)high.*q4.*\\.gguf$"),
            p("vae", "Comfy-Org/Wan_2.1_ComfyUI_repackaged", "split_files/vae/wan_2\\.1_vae\\.safetensors$"),
            p("t5xxl", "city96/umt5-xxl-encoder-gguf", "(?i)Q8_0\\.gguf$", "(?i)Q5_K_M\\.gguf$", "(?i)\\.gguf$"),
        ),
        prof("Wan 2.2 A14B", video = true).copy(steps = 10, highNoiseSteps = 10, cfg = 3.5f, sampler = "euler",
            width = 832, height = 480, frames = 33, fps = 16, negative = VIDEO_NEGATIVE),
    ),
)

/** Роли компонентов с понятными названиями. */
val COMPONENT_ROLES = listOf(
    "model" to "Модель целиком (один файл)",
    "diffusion_model" to "Диффузионная модель",
    "high_noise_diffusion_model" to "Вторая модель (high noise, Wan 2.2 A14B)",
    "vae" to "VAE",
    "taesd" to "TAESD (быстрый VAE)",
    "clip_l" to "CLIP-L",
    "clip_g" to "CLIP-G",
    "t5xxl" to "T5-XXL / UMT5",
    "llm" to "Языковая модель-энкодер (Qwen и др.)",
    "llm_vision" to "Зрение энкодера (mmproj)",
    "clip_vision" to "CLIP Vision (Wan 2.1 I2V)",
)

fun SdModelFiles.with(role: String, path: String?): SdModelFiles = when (role) {
    "model" -> copy(model = path)
    "diffusion_model" -> copy(diffusionModel = path)
    "high_noise_diffusion_model" -> copy(highNoiseDiffusionModel = path)
    "vae" -> copy(vae = path)
    "taesd" -> copy(taesd = path)
    "clip_l" -> copy(clipL = path)
    "clip_g" -> copy(clipG = path)
    "t5xxl" -> copy(t5xxl = path)
    "llm" -> copy(llm = path)
    "llm_vision" -> copy(llmVision = path)
    "clip_vision" -> copy(clipVision = path)
    else -> this
}
