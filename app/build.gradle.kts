plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.neuropocket.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neuropocket.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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

    buildTypes {
        release {
            isMinifyEnabled = false
            // Для публикации замените на собственную подпись
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // CPU-бэкенды ggml загружаются динамически из nativeLibraryDir,
        // поэтому .so-файлы должны быть распакованы при установке.
        jniLibs.useLegacyPackaging = true
        // libc++_shared.so есть и в :app, и в :sdengine — берём один
        jniLibs.pickFirsts += "**/libc++_shared.so"
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
