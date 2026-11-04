import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.3"
}

// sherpa-onnx (распознавание и синтез речи) — Java-часть скачивается при первой сборке
val sherpaVersion = "1.13.8"
val sherpaJar = file("libs/sherpa-onnx-jvm-$sherpaVersion.jar")
if (!sherpaJar.exists()) {
    sherpaJar.parentFile.mkdirs()
    logger.lifecycle("Загрузка sherpa-onnx $sherpaVersion…")
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-jvm-$sherpaVersion.jar"
    val tmp = file("libs/sherpa.tmp")
    uri(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    tmp.renameTo(sherpaJar)
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDirs(
            "src/main/kotlin",
            // Общий код с приложением для Android
            "../shared/llm",
            "../shared/sd",
            "../shared/hub",
            "../shared/speech",
        )
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("androidx.annotation:annotation-jvm:1.9.1")
    implementation("org.json:json:20240303")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("org.jcodec:jcodec-javase:0.2.5")
    implementation(files(sherpaJar))
}

compose.desktop {
    application {
        mainClass = "com.neuropocket.desktop.MainKt"
        jvmArgs += listOf("-Xss16m", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "NeuroPocket"
            packageVersion = "1.0.0"
            description = "Нейросети на компьютере: чат, зрение, картинки, видео, речь"
            vendor = "NeuroPocket"
            // Нативные библиотеки (llama.cpp, stable-diffusion.cpp, sherpa-onnx) — resources/windows-x64
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules(
                "java.instrument", "java.management", "java.naming", "java.sql",
                "jdk.unsupported", "jdk.crypto.ec", "java.net.http", "jdk.accessibility",
            )
            windows {
                menu = true
                menuGroup = "NeuroPocket"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6b1f9c3e-8a42-4f0e-9d4b-2c7a5e1d3f90"
                iconFile.set(project.layout.projectDirectory.file("icon.ico"))
            }
        }
    }
}
