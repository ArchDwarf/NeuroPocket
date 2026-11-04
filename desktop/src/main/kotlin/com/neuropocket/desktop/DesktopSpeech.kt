package com.neuropocket.desktop

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.neuropocket.speech.SpeechModel
import com.neuropocket.speech.SpeechModelType
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Распознавание и синтез речи на компьютере (sherpa-onnx, без интернета).
 */
class DesktopSpeechEngine(private val threads: () -> Int) {

    init {
        // Нативные библиотеки sherpa-onnx лежат рядом с остальными DLL
        System.setProperty("sherpa_onnx.native.path", NativeDir.dir.absolutePath)
    }

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "speech") }.asCoroutineDispatcher()
    private var recognizer: OfflineRecognizer? = null
    private var asrDir: String? = null
    private var tts: OfflineTts? = null
    private var ttsDir: String? = null

    @Volatile private var recording = false
    @Volatile private var playLine: SourceDataLine? = null

    // ===================== Распознавание =====================

    private fun asrConfig(m: SpeechModel): OfflineRecognizerConfig {
        val b = OfflineModelConfig.builder().setTokens(m["tokens"]).setNumThreads(threads().coerceIn(1, 8))
        when (m.type) {
            SpeechModelType.WHISPER -> b.setWhisper(
                OfflineWhisperModelConfig.builder().setEncoder(m["encoder"]).setDecoder(m["decoder"])
                    .setLanguage("").setTask("transcribe").build()
            )
            SpeechModelType.TRANSDUCER -> {
                b.setTransducer(
                    OfflineTransducerModelConfig.builder().setEncoder(m["encoder"]).setDecoder(m["decoder"])
                        .setJoiner(m["joiner"]).build()
                )
                if (m["modelType"].isNotEmpty()) b.setModelType(m["modelType"])
            }
            SpeechModelType.SENSE_VOICE -> b.setSenseVoice(OfflineSenseVoiceModelConfig.builder().setModel(m["model"]).build())
            SpeechModelType.PARAFORMER -> b.setParaformer(OfflineParaformerModelConfig.builder().setModel(m["model"]).build())
            SpeechModelType.ZIPFORMER_CTC -> b.setZipformerCtc(OfflineZipformerCtcModelConfig.builder().setModel(m["model"]).build())
            else -> b.setNemo(OfflineNemoEncDecCtcModelConfig.builder().setModel(m["model"]).build())
        }
        return OfflineRecognizerConfig.builder().setOfflineModelConfig(b.build()).build()
    }

    suspend fun recognize(model: SpeechModel, samples: FloatArray, sampleRate: Int): String = withContext(dispatcher) {
        if (asrDir != model.dir.path) {
            recognizer?.release()
            recognizer = OfflineRecognizer(asrConfig(model))
            asrDir = model.dir.path
        }
        val r = recognizer!!
        // Длинные записи режем на куски по 25 с: так быстрее и стабильнее (особенно для Whisper)
        val chunk = sampleRate * 25
        val sb = StringBuilder()
        var pos = 0
        while (pos < samples.size) {
            val end = minOf(samples.size, pos + chunk)
            val s = r.createStream()
            try {
                s.acceptWaveform(samples.copyOfRange(pos, end), sampleRate)
                r.decode(s)
                val t = r.getResult(s).text.trim()
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)
                }
            } finally {
                s.release()
            }
            pos = end
        }
        sb.toString()
    }

    /** Записывает с микрофона (16 кГц, моно), пока не вызван [stopRecording]. */
    suspend fun record(maxSeconds: Int = 600, onLevel: (Float) -> Unit = {}): FloatArray = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val rate = 16000f
        val format = AudioFormat(rate, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) throw IllegalStateException("Микрофон не найден")
        val line = AudioSystem.getLine(info) as TargetDataLine
        line.open(format)
        line.start()
        recording = true
        val bytes = java.io.ByteArrayOutputStream()
        val buf = ByteArray(3200)
        try {
            while (recording && isActive && bytes.size() < rate.toInt() * 2 * maxSeconds) {
                val n = line.read(buf, 0, buf.size)
                if (n <= 0) continue
                bytes.write(buf, 0, n)
                var peak = 0
                var i = 0
                while (i + 1 < n) {
                    val v = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                    peak = maxOf(peak, kotlin.math.abs(v.toShort().toInt()))
                    i += 2
                }
                onLevel(peak / 32768f)
            }
        } finally {
            line.stop()
            line.close()
            recording = false
        }
        val b = bytes.toByteArray()
        FloatArray(b.size / 2) { i -> ((b[i * 2].toInt() and 0xFF) or (b[i * 2 + 1].toInt() shl 8)).toShort() / 32768f }
    }

    fun stopRecording() {
        recording = false
    }

    // ===================== Синтез =====================

    private fun ttsConfig(m: SpeechModel): OfflineTtsConfig {
        val b = OfflineTtsModelConfig.builder().setNumThreads(threads().coerceIn(1, 8))
        when (m.type) {
            SpeechModelType.KOKORO -> b.setKokoro(
                OfflineTtsKokoroModelConfig.builder().setModel(m["model"]).setVoices(m["voices"]).setTokens(m["tokens"])
                    .setDataDir(m["dataDir"]).setLexicon(m["lexicon"]).build()
            )
            SpeechModelType.KITTEN -> b.setKitten(
                OfflineTtsKittenModelConfig.builder().setModel(m["model"]).setVoices(m["voices"]).setTokens(m["tokens"])
                    .setDataDir(m["dataDir"]).build()
            )
            SpeechModelType.MATCHA -> b.setMatcha(
                OfflineTtsMatchaModelConfig.builder().setAcousticModel(m["acousticModel"]).setVocoder(m["vocoder"])
                    .setTokens(m["tokens"]).setDataDir(m["dataDir"]).setLexicon(m["lexicon"]).build()
            )
            else -> b.setVits(
                OfflineTtsVitsModelConfig.builder().setModel(m["model"]).setTokens(m["tokens"])
                    .setDataDir(m["dataDir"]).setLexicon(m["lexicon"]).build()
            )
        }
        return OfflineTtsConfig.builder().setModel(b.build()).build()
    }

    fun numSpeakers(): Int = tts?.numSpeakers ?: 1

    suspend fun synthesize(model: SpeechModel, text: String, speed: Float = 1f, speaker: Int = 0): Pair<FloatArray, Int> =
        withContext(dispatcher) {
            if (ttsDir != model.dir.path) {
                tts?.release()
                tts = OfflineTts(ttsConfig(model))
                ttsDir = model.dir.path
            }
            val t = tts!!
            val audio = t.generate(text, speaker.coerceIn(0, maxOf(0, t.numSpeakers - 1)), speed)
            audio.samples to audio.sampleRate
        }

    /** Проигрывает звук; возвращается после окончания или остановки. */
    suspend fun play(samples: FloatArray, rate: Int) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        stopPlayback()
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format, rate)
        playLine = line
        line.start()
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }
        var off = 0
        while (off < bytes.size && playLine === line && isActive) {
            val n = minOf(rate, bytes.size - off)
            line.write(bytes, off, n)
            off += n
        }
        if (playLine === line) {
            line.drain()
            stopPlayback()
        }
    }

    fun stopPlayback() {
        val l = playLine
        playLine = null
        try {
            l?.stop()
            l?.close()
        } catch (_: Exception) {
        }
    }

    fun release() {
        stopRecording()
        stopPlayback()
        recognizer?.release()
        tts?.release()
    }
}
