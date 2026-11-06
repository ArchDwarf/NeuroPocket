package com.neuropocket.desktop

import com.neuropocket.app.engine.ComputeDevice
import com.neuropocket.app.engine.DevicePlacement

/** Перевод настроек «где считать» в параметры движков. */
object Placement {

    /** Чат-модель (llama.cpp). */
    fun chat(s: DesktopSettings, devices: List<ComputeDevice>): DevicePlacement {
        val allGpus = devices.filter { it.isGpu }.map { it.name }
        val chosen = s.chatGpus.filter { it in allGpus }.ifEmpty { allGpus }
        val split = when {
            chosen.size <= 1 -> 0
            s.chatSplit == "row" -> 2
            else -> 1
        }
        val ts = s.chatTensorSplit.takeIf { it.size == chosen.size && chosen.size > 1 && it.any { v -> v > 0f } }
        return when (s.chatDevice) {
            "cpu" -> DevicePlacement(devices = emptyList(), gpuLayers = 0)
            "gpu" -> DevicePlacement(devices = chosen, gpuLayers = 999, splitMode = split, tensorSplit = ts)
            "hybrid" -> DevicePlacement(devices = chosen, gpuLayers = s.gpuLayers.coerceIn(1, 998), splitMode = split, tensorSplit = ts)
            else -> DevicePlacement(devices = null, gpuLayers = 999, splitMode = 1)
        }
    }

    /** Картинки и видео (stable-diffusion.cpp): строка бэкенда и режим деления. */
    fun sd(s: DesktopSettings): Pair<String, String> {
        if (s.sdDevice == "cpu") return "cpu" to ""
        val gpus = if (s.sdDevice == "gpu") s.sdGpus else emptyList()
        val parts = mutableListOf<String>()
        gpus.firstOrNull()?.let { parts += "all=$it" }
        if (gpus.size > 1) parts += "diffusion=" + gpus.joinToString("&")
        if (s.sdTextEncoder != "same") parts += "te=${s.sdTextEncoder}"
        if (s.sdVae != "same") parts += "vae=${s.sdVae}"
        val split = if (gpus.size > 1) s.sdSplit else ""
        return parts.joinToString(",") to split
    }

    /** Короткое описание для заголовков. */
    fun chatLabel(s: DesktopSettings, devices: List<ComputeDevice>): String {
        val gpus = devices.filter { it.isGpu }
        fun names(list: List<String>) = list.joinToString(" + ") { n -> gpus.find { it.name == n }?.description?.take(28) ?: n }
        val chosen = s.chatGpus.filter { n -> gpus.any { it.name == n } }.ifEmpty { gpus.map { it.name } }
        return when {
            s.chatDevice == "cpu" || gpus.isEmpty() -> "процессор"
            s.chatDevice == "hybrid" -> "${names(chosen)} + процессор (${s.gpuLayers} слоёв на видеокарте)"
            s.chatDevice == "gpu" -> names(chosen)
            else -> "авто: " + names(gpus.map { it.name })
        }
    }
}
