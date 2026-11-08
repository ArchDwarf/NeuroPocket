package com.neuropocket.app.engine

/** Кусок ответа модели: обычный текст или блок кода. */
sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Code(val language: String, val code: String, val closed: Boolean) : Segment
}

object Markdown {
    private val fence = Regex("```([\\w+#.-]*)[^\\n]*\\n")

    /** Делит ответ на текст и блоки кода ```lang … ```; незакрытый блок (идёт генерация) тоже считается кодом. */
    fun split(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var pos = 0
        while (pos < text.length) {
            val m = fence.find(text, pos) ?: break
            if (m.range.first > pos) out += Segment.Text(text.substring(pos, m.range.first))
            val bodyStart = m.range.last + 1
            val end = text.indexOf("```", bodyStart)
            if (end < 0) {
                out += Segment.Code(m.groupValues[1], text.substring(bodyStart).trimEnd('\n'), closed = false)
                pos = text.length
            } else {
                out += Segment.Code(m.groupValues[1], text.substring(bodyStart, end).trimEnd('\n'), closed = true)
                pos = end + 3
                if (pos < text.length && text[pos] == '\n') pos++
            }
        }
        if (pos < text.length) out += Segment.Text(text.substring(pos))
        return out.filter { it !is Segment.Text || it.text.isNotBlank() }
    }

    /** Расширение файла для языка блока кода. */
    fun extension(language: String): String = when (language.lowercase()) {
        "python", "py" -> "py"; "kotlin", "kt" -> "kt"; "java" -> "java"; "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"; "c" -> "c"; "cpp", "c++" -> "cpp"; "csharp", "cs", "c#" -> "cs"
        "go" -> "go"; "rust", "rs" -> "rs"; "html" -> "html"; "css" -> "css"; "json" -> "json"
        "sql" -> "sql"; "bash", "sh", "shell" -> "sh"; "powershell", "ps1" -> "ps1"; "bat", "cmd" -> "bat"
        "yaml", "yml" -> "yml"; "xml" -> "xml"; "markdown", "md" -> "md"; "php" -> "php"; "swift" -> "swift"
        "ruby", "rb" -> "rb"; "lua" -> "lua"; "dart" -> "dart"
        else -> "txt"
    }

    /** Язык для блока кода по расширению файла. */
    fun languageFor(ext: String): String = when (ext.lowercase()) {
        "py" -> "python"; "kt", "kts" -> "kotlin"; "js", "jsx" -> "javascript"; "ts", "tsx" -> "typescript"
        "cs" -> "csharp"; "rs" -> "rust"; "rb" -> "ruby"; "sh" -> "bash"; "ps1" -> "powershell"; "yml" -> "yaml"
        "h", "hpp", "cc" -> "cpp"; "htm" -> "html"; "md" -> "markdown"
        "txt", "log", "pdf", "" -> ""
        else -> ext.lowercase()
    }
}
