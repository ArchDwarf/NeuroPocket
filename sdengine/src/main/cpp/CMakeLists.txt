cmake_minimum_required(VERSION 3.22.1)

project("sdengine" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# stable-diffusion.cpp — генерация и редактирование изображений на устройстве.
#
# У stable-diffusion.cpp своя (изменённая) копия ggml, поэтому она собирается
# в отдельном модуле статически, а все её символы скрыты внутри libsdengine.so.
# Так она не конфликтует с ggml из llama.cpp в модуле :app.
#
# Локальные копии (для сборки без интернета):
#   third_party/stable-diffusion.cpp  и  third_party/sd-ggml
# ---------------------------------------------------------------------------
set(SD_CPP_COMMIT  "290148d2e31ca6ef9757d567905776b5f3646db5")
set(SD_GGML_COMMIT "d49c49e1dd361ba3c0a77c90b5b1404b83969aa8")
set(LOCAL_SD_DIR   "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/stable-diffusion.cpp")
set(LOCAL_GGML_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/sd-ggml")

# Скрываем все символы статических библиотек
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(CMAKE_POLICY_DEFAULT_CMP0063 NEW)

set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES        OFF CACHE BOOL "" FORCE)
set(SD_WEBP                  OFF CACHE BOOL "" FORCE)
set(SD_WEBM                  OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE           OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS         OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES      OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Ускоренные инструкции (dotprod, fp16) есть у процессоров примерно с 2018 года
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

include(FetchContent)
if(EXISTS "${LOCAL_GGML_DIR}/CMakeLists.txt")
    set(SD_GGML_DIR "${LOCAL_GGML_DIR}")
else()
    FetchContent_Declare(sd_ggml
        URL https://github.com/leejet/ggml/archive/${SD_GGML_COMMIT}.tar.gz)
    FetchContent_GetProperties(sd_ggml)
    if(NOT sd_ggml_POPULATED)
        FetchContent_Populate(sd_ggml)
    endif()
    set(SD_GGML_DIR "${sd_ggml_SOURCE_DIR}")
endif()
set(SD_GGML_SOURCE_DIR "${SD_GGML_DIR}" CACHE PATH "" FORCE)

if(EXISTS "${LOCAL_SD_DIR}/CMakeLists.txt")
    add_subdirectory("${LOCAL_SD_DIR}" build-sd)
else()
    FetchContent_Declare(sd_cpp
        URL https://github.com/leejet/stable-diffusion.cpp/archive/${SD_CPP_COMMIT}.tar.gz)
    FetchContent_GetProperties(sd_cpp)
    if(NOT sd_cpp_POPULATED)
        FetchContent_Populate(sd_cpp)
        add_subdirectory(${sd_cpp_SOURCE_DIR} ${sd_cpp_BINARY_DIR})
    endif()
endif()

add_library(sdengine SHARED sd_jni.cpp)
target_link_libraries(sdengine PRIVATE stable-diffusion android log)
# Не экспортировать символы статических библиотек (ggml, stable-diffusion)
target_link_options(sdengine PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
