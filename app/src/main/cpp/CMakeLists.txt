cmake_minimum_required(VERSION 3.22.1)

project("neuropocket" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp — движок, который запускает GGUF-модели прямо на устройстве.
#
# Если папка third_party/llama.cpp существует (например, git submodule или
# распакованный архив) — используется она (работает без интернета).
# Иначе исходники скачиваются автоматически при первой сборке.
# ---------------------------------------------------------------------------
set(LLAMA_CPP_COMMIT "785322d17f2d51e5185cf996a1f4e46d87640841")
set(LOCAL_LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")

# Параметры сборки llama.cpp для Android
set(BUILD_SHARED_LIBS      ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP        OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL          OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
# Несколько вариантов CPU-бэкенда (armv8.0 / dotprod / i8mm / sve ...):
# во время работы выбирается самый быстрый, который поддерживает процессор.
set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)

if(EXISTS "${LOCAL_LLAMA_DIR}/CMakeLists.txt")
    message(STATUS "llama.cpp: используется локальная копия ${LOCAL_LLAMA_DIR}")
    add_subdirectory("${LOCAL_LLAMA_DIR}" build-llama)
else()
    message(STATUS "llama.cpp: загрузка исходников ${LLAMA_CPP_COMMIT}")
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        URL https://github.com/ggml-org/llama.cpp/archive/${LLAMA_CPP_COMMIT}.tar.gz
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(neuropocket SHARED llm_jni.cpp)

target_link_libraries(neuropocket
    llama
    ggml
    android
    log)
