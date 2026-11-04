package com.neuropocket.sd

/** Загрузка нативной библиотеки stable-diffusion.cpp на Android. */
internal object SdLibrary {
    fun load() {
        System.loadLibrary("sdengine")
    }
}
