plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// sherpa-onnx — распознавание и синтез речи. Готовая библиотека скачивается
// с GitHub при первой сборке (≈50 МБ, в репозиторий не кладётся).
val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    logger.lifecycle("Загрузка sherpa-onnx $sherpaVersion…")
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    val tmp = file("libs/sherpa.tmp")
    uri(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    tmp.renameTo(sherpaAar)
}

android {
    namespace = "com.neuropocket.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neuropocket.app"
        minSdk = 28
        targetSdk = 36
        // Номер сборки растёт с каждой сборкой на GitHub — телефон ставит её как обновление
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 100
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

        ndk {
            // Только arm64-v8a: это все современные телефоны (сборка в 2 раза быстрее)
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // llama.cpp всегда собирается в Release, даже в debug-сборке (иначе в разы медленнее)
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Постоянная подпись: без неё каждая сборка подписывалась новым ключом,
    // и телефон отказывался ставить её поверх прежней («Приложение не установлено»).
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "neuropocket"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    packaging {
        // CPU-бэкенды ggml загружаются динамически из nativeLibraryDir,
        // поэтому .so-файлы должны быть распакованы при установке.
        jniLibs.useLegacyPackaging = true
        // libc++_shared.so есть и в :app, и в :sdengine — берём один
        jniLibs.pickFirsts += "**/libc++_shared.so"
        jniLibs.pickFirsts += "**/libonnxruntime.so"
    }

    sourceSets {
        getByName("main") {
            // Общий код с компьютерной версией
            java.srcDirs("../shared/llm", "../shared/hub", "../shared/speech")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sdengine"))
    implementation(files(sherpaAar))
    implementation(libs.commons.compress)
    implementation("org.jcodec:jcodec:0.2.5")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
