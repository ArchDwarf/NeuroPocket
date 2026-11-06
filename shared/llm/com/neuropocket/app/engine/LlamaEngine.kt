package com.neuropocket.app.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Картинка для модели с постоянным id (чтобы не кодировать её повторно). */
class ChatImage(val id: String, val rgb: ByteArray, val width: Int, val height: Int)

/**
 * Реплика переписки. В [content] на месте каждой картинки стоит
 * [LlamaEngine.marker]; [images] перечислены в том же порядке.
 */
data class ChatTurn(val role: String, val content: String, val images: List<ChatImage> = emptyList())

data class LoadedModel(val info: String, val hasVision: Boolean)

/** Устройство для вычислений (процессор, видеокарта…). */
data class ComputeDevice(
    val name: String,
    val description: String,
    /** 0 — процессор, 1 — видеокарта, 2 — встроенная видеокарта, 3 — ускоритель. */
    val type: Int,
    val freeMb: Long,
    val totalMb: Long,
) {
    val isGpu: Boolean get() = type == 1 || type == 2 || type == 3
}

/**
 * Где считать модель.
 * [devices] — null: все видеокарты; пустой список: только процессор.
 * [gpuLayers] — сколько слоёв на видеокартах (остальное — процессор); 999 — все.
 * [splitMode] — 0 одна видеокарта, 1 по слоям, 2 по строкам (видеокарты работают параллельно).
 * [tensorSplit] — доли модели для каждой видеокарты из [devices]; null — по свободной памяти.
 */
data class DevicePlacement(
    val devices: List<String>? = null,
    val gpuLayers: Int = 0,
    val splitMode: Int = 1,
    val mainGpu: Int = 0,
    val tensorSplit: List<Float>? = null,
)

data class SamplingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
)

sealed interface GenEvent {
    data class Text(val text: String) : GenEvent
    /** code >= 0 — число токенов, иначе код ошибки из [LlamaNative]. */
    data class Done(val code: Int, val millis: Long) : GenEvent
}

/**
 * Обёртка над llama.cpp. Все обращения к нативному коду идут
 * строго последовательно на отдельном потоке.
 */
class LlamaEngine(private val nativeLibDir: String) {

    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(null, r, "llama-engine", 16L * 1024 * 1024)
        }.asCoroutineDispatcher()

    private var initialized = false
    private var handle = 0L

    val isLoaded: Boolean get() = handle != 0L

    /** Маркер места картинки в тексте сообщения. */
    val marker: String by lazy { LlamaNative.mediaMarker() }

    private fun ensureInit() {
        if (!initialized) {
            LlamaNative.init(nativeLibDir)
            initialized = true
        }
    }

    suspend fun listDevices(): List<ComputeDevice> = withContext(dispatcher) {
        ensureInit()
        LlamaNative.listDevices().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 5) null
            else ComputeDevice(p[0], p[1], p[2].toIntOrNull() ?: 0, p[3].toLongOrNull() ?: 0, p[4].toLongOrNull() ?: 0)
        }
    }

    suspend fun systemInfo(): String = withContext(dispatcher) {
        ensureInit()
        LlamaNative.systemInfo()
    }

    /** Загружает модель (и модуль зрения, если есть). Бросает исключение при ошибке. */
    suspend fun load(
        path: String,
        mmprojPath: String?,
        contextSize: Int,
        threads: Int,
        gpuLayers: Int = 0,
        placement: DevicePlacement? = null,
    ): LoadedModel =
        withContext(dispatcher) {
            ensureInit()
            if (handle != 0L) {
                LlamaNative.free(handle)
                handle = 0L
            }
            val pl = placement ?: DevicePlacement(gpuLayers = gpuLayers)
            val h = LlamaNative.load(
                path, mmprojPath, contextSize, threads, pl.gpuLayers,
                pl.devices?.toTypedArray(), pl.splitMode, pl.mainGpu, pl.tensorSplit?.toFloatArray(),
            )
            if (h == 0L) {
                throw IllegalStateException(
                    "Не удалось загрузить модель. Файл повреждён, архитектура не " +
                        "поддерживается или не хватает оперативной памяти."
                )
            }
            handle = h
            LoadedModel(LlamaNative.modelInfo(h), LlamaNative.hasVision(h))
        }

    suspend fun unload() = withContext(dispatcher) {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
    }

    suspend fun resetChat() = withContext(dispatcher) {
        if (handle != 0L) LlamaNative.resetChat(handle)
    }

    /** Останавливает текущую генерацию. Можно вызывать с любого потока. */
    fun stop() {
        if (initialized) LlamaNative.stop()
    }

    fun generate(turns: List<ChatTurn>, params: SamplingParams): Flow<GenEvent> = channelFlow {
        val producer = this
        val started = System.currentTimeMillis()
        val code = withContext(dispatcher) {
            if (handle == 0L) return@withContext LlamaNative.RES_BAD_HANDLE
            val images = turns.flatMap { it.images }
            LlamaNative.generate(
                handle,
                turns.map { it.role }.toTypedArray(),
                turns.map { it.content }.toTypedArray(),
                images.map { it.rgb }.toTypedArray(),
                images.map { it.width }.toIntArray(),
                images.map { it.height }.toIntArray(),
                images.map { it.id }.toTypedArray(),
                params.temperature,
                params.topP,
                params.topK,
                params.repeatPenalty,
                params.maxTokens,
            ) { bytes ->
                producer.trySend(GenEvent.Text(String(bytes, Charsets.UTF_8)))
                producer.isActive
            }
        }
        send(GenEvent.Done(code, System.currentTimeMillis() - started))
    }.buffer(Channel.UNLIMITED)
}
