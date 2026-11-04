package com.neuropocket.sd

import com.neuropocket.desktop.NativeDir

/**
 * Загрузка stable-diffusion.cpp на компьютере: версия для видеокарты (Vulkan),
 * а если Vulkan в системе нет (или видеокарта отключена в настройках) — версия для процессора.
 */
internal object SdLibrary {
    /** Устанавливается до первого обращения к движку. */
    @Volatile var preferGpu: Boolean = true

    /** Какая версия загружена: "Vulkan" или "CPU". */
    @Volatile var loadedVariant: String = ""
        private set

    fun load() {
        NativeDir.loadRuntime()
        if (preferGpu) {
            try {
                if (NativeDir.loadIfExists("sdengine_vk")) {
                    loadedVariant = "Vulkan"
                    return
                }
            } catch (e: UnsatisfiedLinkError) {
                System.err.println("Vulkan недоступен, используется процессор: ${e.message}")
            }
        }
        if (!NativeDir.loadIfExists("sdengine_cpu")) {
            throw UnsatisfiedLinkError("Не найдена библиотека sdengine в ${NativeDir.dir}")
        }
        loadedVariant = "CPU"
    }
}
