plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.neuropocket.sd"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        ndk {
            // Только arm64-v8a: это все современные телефоны (сборка в 2 раза быстрее)
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // stable-diffusion.cpp всегда собирается в Release (иначе в разы медленнее)
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
}
