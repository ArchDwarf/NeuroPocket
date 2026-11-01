package com.neuropocket.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HfRepo(val id: String, val downloads: Long, val likes: Long)

/**
 * GGUF-модель в репозитории. Большие модели бывают разбиты на части
 * (name-00001-of-00003.gguf) — тогда [parts] содержит все файлы.
 */
data class HfModelFile(
    val repo: String,
    val displayName: String,
    val parts: List<String>,
    val sizeBytes: Long,
) {
    val quant: String? = QUANT_REGEX.find(displayName)?.value?.uppercase()
    val isRecommendedQuant: Boolean = quant != null && quant in setOf("Q4_K_M", "Q4_K_S", "Q4_0", "IQ4_XS", "Q4_1")

    companion object {
        private val QUANT_REGEX =
            Regex("(?i)(IQ\\d_[A-Z]+|Q\\d_K_[SML]|Q\\d_K|Q\\d_\\d|Q\\d|F16|BF16|F32)")
    }
}

/** Рекомендуемые небольшие модели, которые хорошо работают на телефонах. */
data class Suggested(val repo: String, val title: String, val note: String)

val SUGGESTED_MODELS = listOf(
    Suggested("bartowski/Qwen2.5-0.5B-Instruct-GGUF", "Qwen 2.5 · 0.5B", "≈0.4 ГБ · для любых телефонов"),
    Suggested("bartowski/Llama-3.2-1B-Instruct-GGUF", "Llama 3.2 · 1B", "≈0.8 ГБ · быстрая"),
    Suggested("unsloth/gemma-3-1b-it-GGUF", "Gemma 3 · 1B", "≈0.8 ГБ · хороший русский"),
    Suggested("unsloth/Qwen3-1.7B-GGUF", "Qwen 3 · 1.7B", "≈1.1 ГБ · умеет рассуждать"),
    Suggested("bartowski/gemma-2-2b-it-GGUF", "Gemma 2 · 2B", "≈1.7 ГБ"),
    Suggested("bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama 3.2 · 3B", "≈2 ГБ · от 6 ГБ ОЗУ"),
    Suggested("unsloth/Qwen3-4B-GGUF", "Qwen 3 · 4B", "≈2.5 ГБ · от 8 ГБ ОЗУ"),
)

object HuggingFace {
    private const val BASE = "https://huggingface.co"
    private val SPLIT_REGEX = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

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

    suspend fun search(query: String, token: String): List<HfRepo> = withContext(Dispatchers.IO) {
        val q = if (query.isBlank()) "" else "search=${enc(query.trim())}&"
        val json = JSONArray(
            get("$BASE/api/models?${q}filter=gguf&sort=downloads&direction=-1&limit=50", token)
        )
        List(json.length()) { i ->
            val o = json.getJSONObject(i)
            HfRepo(
                id = o.optString("id", o.optString("modelId")),
                downloads = o.optLong("downloads"),
                likes = o.optLong("likes"),
            )
        }.filter { it.id.isNotBlank() }
    }

    /** Список GGUF-моделей в репозитории (без mmproj-файлов для картинок). */
    suspend fun listFiles(repo: String, token: String): List<HfModelFile> =
        withContext(Dispatchers.IO) {
            val json = JSONArray(get("$BASE/api/models/$repo/tree/main?recursive=true", token))
            val files = mutableListOf<Pair<String, Long>>()
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                if (o.optString("type") != "file") continue
                val path = o.optString("path")
                if (!path.endsWith(".gguf", ignoreCase = true)) continue
                if (path.substringAfterLast('/').contains("mmproj", ignoreCase = true)) continue
                val size = o.optJSONObject("lfs")?.optLong("size") ?: o.optLong("size")
                files += path to size
            }

            val singles = mutableListOf<HfModelFile>()
            val splits = linkedMapOf<String, MutableList<Pair<String, Long>>>()
            for ((path, size) in files) {
                val m = SPLIT_REGEX.matchEntire(path)
                if (m == null) {
                    singles += HfModelFile(repo, path.substringAfterLast('/'), listOf(path), size)
                } else {
                    splits.getOrPut(m.groupValues[1]) { mutableListOf() } += path to size
                }
            }
            for ((base, parts) in splits) {
                val sorted = parts.sortedBy { it.first }
                singles += HfModelFile(
                    repo = repo,
                    displayName = base.substringAfterLast('/') + ".gguf (${sorted.size} части)",
                    parts = sorted.map { it.first },
                    sizeBytes = sorted.sumOf { it.second },
                )
            }
            singles.sortedBy { it.sizeBytes }
        }
}
