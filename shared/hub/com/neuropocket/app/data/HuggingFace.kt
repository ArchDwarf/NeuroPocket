package com.neuropocket.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Чат-модель (GGUF для llama.cpp) или модель для картинок (stable-diffusion.cpp). */
enum class ModelKind { CHAT, IMAGE }

data class HfRepo(val id: String, val downloads: Long, val likes: Long)

/**
 * Файл модели в репозитории. Большие GGUF бывают разбиты на части
 * (name-00001-of-00003.gguf) — тогда [parts] содержит все файлы.
 * [mmproj] — модуль зрения, который нужно скачать вместе с чат-моделью.
 */
data class HfModelFile(
    val repo: String,
    val kind: ModelKind,
    val displayName: String,
    val parts: List<String>,
    val sizeBytes: Long,
    val mmproj: String? = null,
    val mmprojSize: Long = 0,
) {
    val quant: String? = QUANT_REGEX.find(displayName)?.value?.uppercase()
    val isRecommendedQuant: Boolean = when (kind) {
        ModelKind.CHAT -> quant != null && quant in setOf("Q4_K_M", "Q4_K_S", "Q4_0", "IQ4_XS", "Q4_1")
        ModelKind.IMAGE -> quant != null && quant in setOf("Q8_0", "Q4_0", "Q4_1", "Q5_0", "F16")
    }
    val totalSize: Long get() = sizeBytes + mmprojSize

    companion object {
        private val QUANT_REGEX =
            Regex("(?i)(IQ\\d_[A-Z]+|Q\\d_K_[SML]|Q\\d_K|Q\\d_\\d|Q\\d|F16|BF16|F32|FP16)")
    }
}

/** Рекомендуемые модели, которые реально работают на телефонах. */
data class Suggested(val repo: String, val title: String, val note: String, val kind: ModelKind)

val SUGGESTED_CHAT = listOf(
    Suggested("bartowski/Qwen2.5-0.5B-Instruct-GGUF", "Qwen 2.5 · 0.5B", "≈0.4 ГБ · для любых телефонов", ModelKind.CHAT),
    Suggested("bartowski/Llama-3.2-1B-Instruct-GGUF", "Llama 3.2 · 1B", "≈0.8 ГБ · быстрая", ModelKind.CHAT),
    Suggested("unsloth/gemma-3-1b-it-GGUF", "Gemma 3 · 1B", "≈0.8 ГБ · хороший русский", ModelKind.CHAT),
    Suggested("unsloth/Qwen3-1.7B-GGUF", "Qwen 3 · 1.7B", "≈1.1 ГБ · умеет рассуждать", ModelKind.CHAT),
    Suggested("bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama 3.2 · 3B", "≈2 ГБ · от 6 ГБ ОЗУ", ModelKind.CHAT),
    Suggested("unsloth/Qwen3-4B-GGUF", "Qwen 3 · 4B", "≈2.5 ГБ · от 8 ГБ ОЗУ", ModelKind.CHAT),
)

/** Модели, которые видят фото и видео (скачиваются вместе с mmproj). */
val SUGGESTED_VISION = listOf(
    Suggested("ggml-org/SmolVLM2-500M-Video-Instruct-GGUF", "👁 SmolVLM2 · 500M", "≈0.6 ГБ · фото и видео, очень быстрая · отвечает по-английски", ModelKind.CHAT),
    Suggested("ggml-org/InternVL3-1B-Instruct-GGUF", "👁 InternVL3 · 1B", "≈1 ГБ · фото", ModelKind.CHAT),
    Suggested("ggml-org/gemma-4-E2B-it-GGUF", "👁 Gemma 4 · E2B", "≈3 ГБ · фото, хороший русский · от 6 ГБ ОЗУ", ModelKind.CHAT),
    Suggested("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "👁 Qwen2.5-VL · 3B", "≈2.7 ГБ · фото и видео, читает текст · от 6 ГБ ОЗУ", ModelKind.CHAT),
    Suggested("ggml-org/gemma-3-4b-it-GGUF", "👁 Gemma 3 · 4B", "≈3.2 ГБ · фото · от 8 ГБ ОЗУ", ModelKind.CHAT),
)

/** Модели для генерации и редактирования изображений. */
val SUGGESTED_IMAGE = listOf(
    Suggested("concedo/sdxs-512-tinySDdistilled-GGUF", "SDXS 512", "≈0.3–0.6 ГБ · картинка за секунды (1 шаг)", ModelKind.IMAGE),
    Suggested("Green-Sky/SD-Turbo-GGUF", "SD-Turbo", "≈1–2 ГБ · 1–4 шага, быстрая", ModelKind.IMAGE),
    Suggested("second-state/stable-diffusion-v1-5-GGUF", "Stable Diffusion 1.5", "≈1–2 ГБ · классика, перерисовка и маска", ModelKind.IMAGE),
    Suggested("timbrooks/instruct-pix2pix", "Instruct-Pix2Pix", "редактирование по инструкции («сделай зиму») · большой файл, от 12 ГБ ОЗУ", ModelKind.IMAGE),
)

object HuggingFace {
    private const val BASE = "https://huggingface.co"
    private val SPLIT_REGEX = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
    private val IMAGE_EXT = listOf(".safetensors", ".ckpt", ".gguf")
    private val IMAGE_SKIP = listOf("lora", "vae", "controlnet", "clip", "t5", "embedding", "mmproj", "upscale", "esrgan")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Путь в репозитории → URL (каждый сегмент кодируется отдельно). */
    private fun encodePath(path: String) = path.split('/').joinToString("/") { enc(it) }

    fun downloadUrl(repo: String, path: String): String =
        "$BASE/$repo/resolve/main/${encodePath(path)}?download=true"

    private fun get(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json")
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                throw IOException("Нет доступа ($code). Для закрытых моделей укажите токен Hugging Face в настройках.")
            }
            if (code == 404) throw IOException("Репозиторий не найден")
            if (code !in 200..299) throw IOException("Ошибка сервера: HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun search(query: String, kind: ModelKind, token: String): List<HfRepo> = withContext(Dispatchers.IO) {
        val q = if (query.isBlank()) "" else "search=${enc(query.trim())}&"
        val filter = when (kind) {
            ModelKind.CHAT -> "filter=gguf"
            ModelKind.IMAGE -> "pipeline_tag=text-to-image"
        }
        val json = JSONArray(get("$BASE/api/models?$q$filter&sort=downloads&direction=-1&limit=50", token))
        List(json.length()) { i ->
            val o = json.getJSONObject(i)
            HfRepo(
                id = o.optString("id", o.optString("modelId")),
                downloads = o.optLong("downloads"),
                likes = o.optLong("likes"),
            )
        }.filter { it.id.isNotBlank() }
    }

    private data class RemoteFile(val path: String, val size: Long) {
        val name: String get() = path.substringAfterLast('/')
    }

    private suspend fun tree(repo: String, token: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val json = JSONArray(get("$BASE/api/models/$repo/tree/main?recursive=true", token))
        buildList {
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                if (o.optString("type") != "file") continue
                val size = o.optJSONObject("lfs")?.optLong("size") ?: o.optLong("size")
                add(RemoteFile(o.optString("path"), size))
            }
        }
    }

    suspend fun listFiles(repo: String, kind: ModelKind, token: String): List<HfModelFile> =
        when (kind) {
            ModelKind.CHAT -> listChatFiles(repo, token)
            ModelKind.IMAGE -> listImageFiles(repo, token)
        }

    /** GGUF-модели репозитория. Если в нём есть mmproj — модель «видит» картинки. */
    private suspend fun listChatFiles(repo: String, token: String): List<HfModelFile> {
        val all = tree(repo, token).filter { it.path.endsWith(".gguf", ignoreCase = true) }
        val (projectors, models) = all.partition { it.name.contains("mmproj", ignoreCase = true) }
        // Для телефона лучше компактный модуль зрения: Q8 → F16 → BF16 → остальное
        val mmproj = projectors.minByOrNull { p ->
            val n = p.name.lowercase()
            when {
                "q8" in n -> 0
                "f16" in n && "bf16" !in n -> 1
                "bf16" in n -> 2
                else -> 3
            } * 1_000_000_000_000L + p.size
        }

        val result = mutableListOf<HfModelFile>()
        val splits = linkedMapOf<String, MutableList<RemoteFile>>()
        for (f in models) {
            val m = SPLIT_REGEX.matchEntire(f.path)
            if (m == null) {
                result += HfModelFile(repo, ModelKind.CHAT, f.name, listOf(f.path), f.size, mmproj?.path, mmproj?.size ?: 0)
            } else {
                splits.getOrPut(m.groupValues[1]) { mutableListOf() } += f
            }
        }
        for ((base, parts) in splits) {
            val sorted = parts.sortedBy { it.path }
            result += HfModelFile(
                repo = repo,
                kind = ModelKind.CHAT,
                displayName = base.substringAfterLast('/') + ".gguf (${sorted.size} части)",
                parts = sorted.map { it.path },
                sizeBytes = sorted.sumOf { it.size },
                mmproj = mmproj?.path,
                mmprojSize = mmproj?.size ?: 0,
            )
        }
        return result.sortedBy { it.sizeBytes }
    }

    /** Однофайловые модели Stable Diffusion (.safetensors / .ckpt / .gguf). */
    private suspend fun listImageFiles(repo: String, token: String): List<HfModelFile> =
        tree(repo, token)
            .filter { f -> IMAGE_EXT.any { f.path.endsWith(it, ignoreCase = true) } }
            // Файлы в подпапках — это компоненты формата diffusers, их загрузить нельзя
            .filter { f -> !f.path.contains('/') }
            .filter { f -> IMAGE_SKIP.none { f.name.contains(it, ignoreCase = true) } }
            .filter { f -> f.size > 50L * 1024 * 1024 }
            .map { f -> HfModelFile(repo, ModelKind.IMAGE, f.name, listOf(f.path), f.size) }
            .sortedBy { it.sizeBytes }
}
