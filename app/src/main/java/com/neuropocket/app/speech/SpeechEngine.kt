package com.neuropocket.app.speech

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * Распознавание и синтез речи на телефоне (sherpa-onnx, без интернета).
 */
class SpeechEngine(private val threads: () -> Int) {

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "speech") }.asCoroutineDispatcher()

    private var recognizer: OfflineRecognizer? = null
    private var asrDir: String? = null
    private var tts: OfflineTts? = null
    private var ttsDir: String? = null

    @Volatile private var track: AudioTrack? = null
    @Volatile private var recording = false

    // ===================== Распознавание =====================

    private fun asrConfig(m: SpeechModel): OfflineRecognizerConfig {
        val mc = OfflineModelConfig(tokens = m["tokens"], numThreads = threads().coerceIn(1, 4))
        when (m.type) {
            SpeechModelType.WHISPER -> mc.whisper = OfflineWhisperModelConfig(
                encoder = m["encoder"], decoder = m["decoder"], language = "", task = "transcribe",
            )
            SpeechModelType.TRANSDUCER -> {
                mc.transducer = OfflineTransducerModelConfig(m["encoder"], m["decoder"], m["joiner"])
                mc.modelType = m["modelType"]
            }
            SpeechModelType.SENSE_VOICE -> mc.senseVoice = OfflineSenseVoiceModelConfig(model = m["model"])
            SpeechModelType.PARAFORMER -> mc.paraformer = OfflineParaformerModelConfig(model = m["model"])
            SpeechModelType.ZIPFORMER_CTC -> mc.zipformerCtc = OfflineZipformerCtcModelConfig(model = m["model"])
            else -> mc.nemo = OfflineNemoEncDecCtcModelConfig(model = m["model"])
        }
        return OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = mc)
    }

    suspend fun recognize(model: SpeechModel, samples: FloatArray, sampleRate: Int): String = withContext(dispatcher) {
        if (asrDir != model.dir.path) {
            recognizer?.release()
            recognizer = null
            recognizer = OfflineRecognizer(config = asrConfig(model))
            asrDir = model.dir.path
        }
        val r = recognizer!!
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            r.decode(stream)
            r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Записывает голос с микрофона (16 кГц, моно), пока не вызван [stopRecording]. */
    @SuppressLint("MissingPermission")
    suspend fun record(maxSeconds: Int = 120, onLevel: (Float) -> Unit = {}): FloatArray = withContext(Dispatchers.IO) {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Микрофон недоступен")
        }
        val out = ArrayList<Short>(rate * 10)
        val buf = ShortArray(rate / 10)
        recording = true
        rec.startRecording()
        try {
            while (recording && coroutineContext.isActive && out.size < rate * maxSeconds) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var peak = 0
                for (i in 0 until n) {
                    out.add(buf[i])
                    peak = maxOf(peak, kotlin.math.abs(buf[i].toInt()))
                }
                onLevel(peak / 32768f)
            }
        } finally {
            rec.stop()
            rec.release()
            recording = false
        }
        FloatArray(out.size) { out[it] / 32768f }
    }

    fun stopRecording() {
        recording = false
    }

    // ===================== Синтез =====================

    private fun ttsConfig(m: SpeechModel): OfflineTtsConfig {
        val mc = OfflineTtsModelConfig(numThreads = threads().coerceIn(1, 4))
        when (m.type) {
            SpeechModelType.KOKORO -> mc.kokoro = OfflineTtsKokoroModelConfig(
                model = m["model"], voices = m["voices"], tokens = m["tokens"], dataDir = m["dataDir"], lexicon = m["lexicon"],
            )
            SpeechModelType.KITTEN -> mc.kitten = OfflineTtsKittenModelConfig(
                model = m["model"], voices = m["voices"], tokens = m["tokens"], dataDir = m["dataDir"],
            )
            SpeechModelType.MATCHA -> mc.matcha = OfflineTtsMatchaModelConfig(
                acousticModel = m["acousticModel"], vocoder = m["vocoder"], tokens = m["tokens"],
                dataDir = m["dataDir"], lexicon = m["lexicon"],
            )
            else -> mc.vits = OfflineTtsVitsModelConfig(
                model = m["model"], tokens = m["tokens"], dataDir = m["dataDir"], lexicon = m["lexicon"],
            )
        }
        return OfflineTtsConfig(model = mc)
    }

    /** Озвучивает текст и сразу проигрывает. Возвращается после окончания воспроизведения. */
    suspend fun speak(model: SpeechModel, text: String, speed: Float = 1f, speaker: Int = 0) {
        val (samples, rate) = synthesize(model, text, speed, speaker)
        play(samples, rate)
    }

    suspend fun synthesize(model: SpeechModel, text: String, speed: Float = 1f, speaker: Int = 0): Pair<FloatArray, Int> =
        withContext(dispatcher) {
            if (ttsDir != model.dir.path) {
                tts?.release()
                tts = null
                tts = OfflineTts(config = ttsConfig(model))
                ttsDir = model.dir.path
            }
            val audio = tts!!.generate(text, sid = speaker.coerceIn(0, maxOf(0, tts!!.numSpeakers() - 1)), speed = speed)
            audio.samples to audio.sampleRate
        }

    private suspend fun play(samples: FloatArray, rate: Int) = withContext(Dispatchers.IO) {
        stopPlayback()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(samples.size * 4, 4096))
            .build()
        track = t
        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        t.play()
        val durationMs = samples.size * 1000L / rate
        val start = System.currentTimeMillis()
        while (track === t && coroutineContext.isActive && System.currentTimeMillis() - start < durationMs + 200) {
            Thread.sleep(50)
        }
        if (track === t) stopPlayback()
    }

    fun stopPlayback() {
        val t = track
        track = null
        try {
            t?.stop()
        } catch (_: Exception) {
        }
        t?.release()
    }

    fun release() {
        stopRecording()
        stopPlayback()
        recognizer?.release()
        tts?.release()
    }
}
