package com.neuropocket.app.engine

/** Распознаёт в сообщении просьбу нарисовать картинку или изменить фото. */
object ChatIntent {

    // Глаголы, которые сами по себе означают «нарисовать»
    private val drawVerb = Regex(
        "(?iU)^\\s*(пожалуйста[,\\s]+|можешь\\s+|а\\s+теперь\\s+|теперь\\s+)?" +
            "(нарисуй|нарисуйте|нарисовать|нарисуешь|изобрази|изобразите|draw|paint|sketch|imagine)\\b",
    )

    // «Сделай/создай/сгенерируй … картинку/изображение/рисунок…»
    private val makeImage = Regex(
        "(?iU)\\b(сделай|сделайте|создай|создайте|сгенерируй|сгенерируйте|сгенерировать|покажи|нужна|нужно|хочу|" +
            "generate|create|make|show|render)\\b.{0,40}?" +
            "\\b(картинк\\w*|изображени\\w*|рисун\\w*|арт\\w*|иллюстраци\\w*|обои|аватар\\w*|логотип\\w*|постер\\w*|" +
            "image\\w*|picture\\w*|drawing\\w*|illustration\\w*|wallpaper\\w*|avatar\\w*|logo\\w*|poster\\w*)\\b",
    )

    // С прикреплённым фото: «перерисуй / измени / добавь / сделай …»
    private val editVerb = Regex(
        "(?iU)^\\s*(пожалуйста[,\\s]+|можешь\\s+|а\\s+теперь\\s+|теперь\\s+)?" +
            "(измени|измените|отредактируй|перерисуй|переделай|добавь|убери|удали|замени|преврати|стилизуй|раскрась|" +
            "поменяй|сделай|сделайте|edit|redraw|make|turn|add|remove|replace|change|convert|colorize)\\b",
    )

    // Вопросы о фото — это не редактирование
    private val question = Regex(
        "(?iU)(\\?|\\b(опиши|описание|что\\s+(на|это|здесь|тут)|кто|сколько|почему|зачем|какой|какая|какие|расскажи|" +
            "переведи|прочитай|распознай|describe|what|who|why|how\\s+many|explain|read)\\b)",
    )

    fun isDrawRequest(text: String, hasPhoto: Boolean): Boolean {
        if (text.isBlank()) return false
        if (drawVerb.containsMatchIn(text) || makeImage.containsMatchIn(text)) return true
        return hasPhoto && editVerb.containsMatchIn(text) && !question.containsMatchIn(text)
    }

    private val commandPrefix = Regex(
        "(?iU)^\\s*(пожалуйста[,\\s]+|можешь\\s+|а\\s+теперь\\s+|теперь\\s+)?" +
            "((нарисуй|нарисуйте|нарисовать|изобрази|изобразите|draw|paint|imagine)(\\s+(мне|me|please))?" +
            "|(сделай|создай|сгенерируй|generate|create|make)(\\s+(мне|me))?\\s+" +
            "(картинку|изображение|рисунок|арт|an?\\s+image|an?\\s+picture|image|picture))[\\s,:.-]*",
    )

    /** Убирает из запроса «нарисуй …», оставляя суть. */
    fun stripCommand(text: String): String = text.replace(commandPrefix, "").trim().ifEmpty { text.trim() }

    /** Нужен ли перевод: модели картинок лучше всего понимают английский. */
    fun needsTranslation(text: String): Boolean = text.any { it.code > 127 && it.isLetter() }

    /** Чистит ответ чат-модели до одной строки описания. */
    fun cleanPrompt(raw: String): String? {
        val noThink = raw.replace(Regex("(?s)<think>.*?(</think>|$)"), "")
        val line = noThink.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val cleaned = line
            .replace(Regex("(?i)^(prompt|instruction|english prompt)\\s*[:：-]\\s*"), "")
            .trim().trim('"', '«', '»', '“', '”', '\'', '`').trim()
        if (cleaned.length < 3 || needsTranslation(cleaned)) return null
        return cleaned.take(400)
    }
}
