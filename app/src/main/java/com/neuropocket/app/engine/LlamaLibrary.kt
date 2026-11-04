package com.neuropocket.app.engine

/** Загрузка нативной библиотеки llama.cpp на Android. */
internal object LlamaLibrary {
    fun load() {
        System.loadLibrary("neuropocket")
    }
}
