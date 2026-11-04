package com.neuropocket.desktop

import java.io.File

/** Папка с нативными библиотеками (DLL) внутри установленного приложения. */
object NativeDir {
    val dir: File by lazy {
        val candidates = listOfNotNull(
            System.getenv("NEUROPOCKET_NATIVE"),
            System.getProperty("compose.application.resources.dir"),
            "resources/windows-x64",
            "desktop/resources/windows-x64",
        )
        candidates.map { File(it) }.firstOrNull { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
            ?: File(candidates.first())
    }

    fun file(name: String): File = File(dir, System.mapLibraryName(name))

    /** Загружает библиотеку по имени без расширения, если файл есть. */
    fun loadIfExists(name: String): Boolean {
        val f = file(name)
        if (!f.exists()) return false
        System.load(f.absolutePath)
        return true
    }
}
