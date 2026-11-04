package com.neuropocket.app.engine

import com.neuropocket.desktop.NativeDir

/**
 * Загрузка llama.cpp на компьютере. Windows не ищет зависимые DLL в папке
 * загружаемой библиотеки, поэтому загружаем их по порядку сами.
 * Бэкенды (процессор, Vulkan) llama.cpp подгружает потом из той же папки.
 */
internal object LlamaLibrary {
    fun load() {
        NativeDir.loadRuntime()
        for (name in listOf("ggml-base", "ggml", "llama", "mtmd")) NativeDir.loadIfExists(name)
        if (!NativeDir.loadIfExists("neuropocket")) {
            throw UnsatisfiedLinkError("Не найдена библиотека neuropocket в ${NativeDir.dir}")
        }
    }
}
