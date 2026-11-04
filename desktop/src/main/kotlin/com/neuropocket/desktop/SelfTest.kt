package com.neuropocket.desktop

import com.neuropocket.app.engine.LlamaEngine
import com.neuropocket.sd.SdEngine
import com.neuropocket.sd.SdLibrary
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Самопроверка без интерфейса: NeuroPocket.exe --selftest <файл отчёта>.
 * Загружает все нативные библиотеки и пишет отчёт. Код выхода 0 — всё в порядке.
 */
object SelfTest {
    fun run(report: File): Int {
        val sb = StringBuilder()
        var ok = true
        fun step(name: String, block: () -> String) {
            try {
                sb.append("OK   ").append(name).append(": ").append(block().replace("\n", " | ").take(400)).append('\n')
            } catch (t: Throwable) {
                ok = false
                sb.append("FAIL ").append(name).append(": ").append(t.toString()).append('\n')
            }
        }
        step("Папка библиотек") {
            val d = NativeDir.dir
            "${d.absolutePath} (${d.listFiles()?.size ?: 0} файлов)"
        }
        step("llama.cpp") { runBlocking { LlamaEngine(NativeDir.dir.absolutePath).systemInfo() } }
        step("stable-diffusion.cpp") {
            val info = runBlocking { SdEngine().systemInfo() }
            "${SdLibrary.loadedVariant}: $info"
        }
        step("sherpa-onnx") {
            System.setProperty("sherpa_onnx.native.path", NativeDir.dir.absolutePath)
            "версия ${com.k2fsa.sherpa.onnx.VersionInfo.getVersion()}, onnxruntime ${com.k2fsa.sherpa.onnx.VersionInfo.getOnnxruntimeVersion()}"
        }
        step("Папка данных") { Paths.root.absolutePath }
        sb.append(if (ok) "РЕЗУЛЬТАТ: OK\n" else "РЕЗУЛЬТАТ: ОШИБКИ\n")
        report.parentFile?.mkdirs()
        report.writeText(sb.toString())
        print(sb)
        return if (ok) 0 else 1
    }
}
