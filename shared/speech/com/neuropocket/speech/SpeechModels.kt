package com.neuropocket.speech

import java.io.File

/** Распознавание речи (ASR) или синтез речи (TTS). */
enum class SpeechKind { ASR, TTS }

/** Модель из каталога sherpa-onnx (архив .tar.bz2 на GitHub). */
data class SpeechCatalogItem(
    val id: String,
    val kind: SpeechKind,
    val title: String,
    val note: String,
    val url: String,
    val sizeMb: Int,
    /** Только для мощных компьютеров. */
    val desktopOnly: Boolean = false,
) {
    /** Имя папки после распаковки. */
    val dirName: String get() = url.substringAfterLast('/').removeSuffix(".tar.bz2")
}

private const val ASR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
private const val TTS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

val SPEECH_CATALOG = listOf(
    SpeechCatalogItem("gigaam-v2", SpeechKind.ASR, "GigaAM v2 (русский)",
        "лучшее распознавание русской речи, быстрое", "$ASR/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19.tar.bz2", 167),
    SpeechCatalogItem("whisper-tiny", SpeechKind.ASR, "Whisper tiny",
        "99 языков, очень быстрый, точность ниже", "$ASR/sherpa-onnx-whisper-tiny.tar.bz2", 116),
    SpeechCatalogItem("whisper-base", SpeechKind.ASR, "Whisper base",
        "99 языков", "$ASR/sherpa-onnx-whisper-base.tar.bz2", 200),
    SpeechCatalogItem("whisper-small", SpeechKind.ASR, "Whisper small",
        "99 языков, хорошая точность", "$ASR/sherpa-onnx-whisper-small.tar.bz2", 610),
    SpeechCatalogItem("whisper-turbo", SpeechKind.ASR, "Whisper large-v3 turbo",
        "99 языков, высокая точность", "$ASR/sherpa-onnx-whisper-turbo.tar.bz2", 1600, desktopOnly = true),
    SpeechCatalogItem("whisper-large-v3", SpeechKind.ASR, "Whisper large-v3",
        "99 языков, максимальная точность, медленный", "$ASR/sherpa-onnx-whisper-large-v3.tar.bz2", 3000, desktopOnly = true),

    SpeechCatalogItem("piper-irina", SpeechKind.TTS, "Ирина (русский, женский)",
        "Piper, естественный голос", "$TTS/vits-piper-ru_RU-irina-medium.tar.bz2", 67),
    SpeechCatalogItem("piper-denis", SpeechKind.TTS, "Денис (русский, мужской)",
        "Piper", "$TTS/vits-piper-ru_RU-denis-medium.tar.bz2", 67),
    SpeechCatalogItem("piper-dmitri", SpeechKind.TTS, "Дмитрий (русский, мужской)",
        "Piper", "$TTS/vits-piper-ru_RU-dmitri-medium.tar.bz2", 67),
    SpeechCatalogItem("piper-ruslan", SpeechKind.TTS, "Руслан (русский, мужской)",
        "Piper", "$TTS/vits-piper-ru_RU-ruslan-medium.tar.bz2", 67),
    SpeechCatalogItem("piper-amy", SpeechKind.TTS, "Amy (English, female)",
        "Piper", "$TTS/vits-piper-en_US-amy-medium.tar.bz2", 67),
    SpeechCatalogItem("piper-lessac", SpeechKind.TTS, "Lessac (English, male)",
        "Piper", "$TTS/vits-piper-en_US-lessac-medium.tar.bz2", 67),
    SpeechCatalogItem("kokoro-en", SpeechKind.TTS, "Kokoro (English, 11 голосов)",
        "очень естественный английский", "$TTS/kokoro-en-v0_19.tar.bz2", 330),
)

/** Тип модели, понятный sherpa-onnx. */
enum class SpeechModelType {
    WHISPER, NEMO_CTC, TRANSDUCER, SENSE_VOICE, PARAFORMER, ZIPFORMER_CTC, VITS, KOKORO, KITTEN, MATCHA
}

/** Распакованная модель: тип и пути к файлам. */
data class SpeechModel(
    val dir: File,
    val kind: SpeechKind,
    val type: SpeechModelType,
    val files: Map<String, String>,
) {
    val name: String get() = dir.name
    val title: String get() = SPEECH_CATALOG.find { it.dirName == dir.name }?.title ?: dir.name
    operator fun get(key: String): String = files[key] ?: ""

    companion object {
        /** Определяет тип модели по содержимому папки. Возвращает null, если не распознана. */
        fun detect(dir: File): SpeechModel? {
            if (!dir.isDirectory) return null
            val all = dir.walkTopDown().maxDepth(2).filter { it.isFile }.toList()
            val onnx = all.filter { it.name.endsWith(".onnx") }
            val name = dir.name.lowercase()
            fun pick(filter: (File) -> Boolean): String? {
                val c = onnx.filter(filter)
                // Предпочитаем сжатые int8-версии: быстрее и меньше памяти
                return (c.firstOrNull { "int8" in it.name } ?: c.firstOrNull())?.absolutePath
            }
            val tokens = all.firstOrNull { it.name == "tokens.txt" }?.absolutePath
                ?: all.firstOrNull { it.name.endsWith("tokens.txt") }?.absolutePath
            val espeak = File(dir, "espeak-ng-data").takeIf { it.isDirectory }?.absolutePath
            val lexicon = all.filter { it.name.startsWith("lexicon") && it.name.endsWith(".txt") }
                .joinToString(",") { it.absolutePath }
            val voices = all.firstOrNull { it.name == "voices.bin" }?.absolutePath

            val encoder = pick { "encoder" in it.name }
            val decoder = pick { "decoder" in it.name }
            val joiner = pick { "joiner" in it.name }

            return when {
                tokens == null -> null
                voices != null -> SpeechModel(
                    dir, SpeechKind.TTS,
                    if ("kitten" in name) SpeechModelType.KITTEN else SpeechModelType.KOKORO,
                    mapOf("model" to (pick { "model" in it.name } ?: onnx.first().absolutePath),
                        "voices" to voices, "tokens" to tokens, "dataDir" to (espeak ?: ""), "lexicon" to lexicon),
                )
                "matcha" in name -> SpeechModel(
                    dir, SpeechKind.TTS, SpeechModelType.MATCHA,
                    mapOf("acousticModel" to (pick { "vocos" !in it.name && "hifigan" !in it.name } ?: ""),
                        "vocoder" to (pick { "vocos" in it.name || "hifigan" in it.name } ?: ""),
                        "tokens" to tokens, "dataDir" to (espeak ?: ""), "lexicon" to lexicon),
                )
                espeak != null || name.startsWith("vits") -> SpeechModel(
                    dir, SpeechKind.TTS, SpeechModelType.VITS,
                    mapOf("model" to (onnx.firstOrNull()?.absolutePath ?: return null),
                        "tokens" to tokens, "dataDir" to (espeak ?: ""), "lexicon" to lexicon),
                )
                encoder != null && decoder != null && joiner != null -> SpeechModel(
                    dir, SpeechKind.ASR, SpeechModelType.TRANSDUCER,
                    mapOf("encoder" to encoder, "decoder" to decoder, "joiner" to joiner, "tokens" to tokens,
                        "modelType" to if ("nemo" in name) "nemo_transducer" else ""),
                )
                "whisper" in name && encoder != null && decoder != null -> SpeechModel(
                    dir, SpeechKind.ASR, SpeechModelType.WHISPER,
                    mapOf("encoder" to encoder, "decoder" to decoder, "tokens" to tokens),
                )
                else -> {
                    val model = pick { true } ?: return null
                    val type = when {
                        "sense-voice" in name || "sensevoice" in name -> SpeechModelType.SENSE_VOICE
                        "paraformer" in name -> SpeechModelType.PARAFORMER
                        "zipformer" in name && "ctc" in name -> SpeechModelType.ZIPFORMER_CTC
                        else -> SpeechModelType.NEMO_CTC
                    }
                    SpeechModel(dir, SpeechKind.ASR, type, mapOf("model" to model, "tokens" to tokens))
                }
            }
        }

        /** Все распознанные модели в папке. */
        fun scan(root: File): List<SpeechModel> =
            root.listFiles { f -> f.isDirectory }?.mapNotNull { detect(it) }?.sortedBy { it.title.lowercase() }
                ?: emptyList()
    }
}
