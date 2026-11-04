# NeuroPocket — нейросети на телефоне и компьютере

NeuroPocket скачивает и запускает нейросети **на вашем устройстве**: всё считается локально,
интернет нужен только для скачивания моделей.

| Вид ИИ | Телефон (Android) | Компьютер (Windows) |
|---|---|---|
| Чат — любая GGUF-модель llama.cpp | ✓ | ✓ на видеокарте |
| Фото и видео в чате (модели со зрением) | ✓ | ✓ |
| Картинки: по тексту, перерисовка, маска, правка по инструкции | ✓ лёгкие модели | ✓ включая FLUX, Qwen-Image, Z-Image, SD3 |
| Генерация видео (Wan 2.1 / 2.2) | — | ✓ |
| Распознавание речи (GigaAM, Whisper…) | ✓ | ✓ |
| Синтез речи (Piper, Kokoro…) | ✓ | ✓ |

Движки: [llama.cpp](https://github.com/ggml-org/llama.cpp) (чат и зрение),
[stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) (картинки и видео),
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (речь).

## Где взять готовые файлы

Всё собирается на GitHub автоматически при каждом изменении — вкладка **Actions**:

- **«Сборка APK»** → Artifacts → `NeuroPocket-apk` — приложение для Android (9+, arm64).
- **«Компьютерная версия (Windows)»** → Artifacts → `NeuroPocket-Windows` — установщики `.msi` и `.exe`.

## Компьютерная версия (Windows)

| Вкладка  | Что умеет |
|----------|-----------|
| Чат      | любая GGUF-модель, фото и видео для моделей со зрением, голосовой ввод, озвучка ответов |
| Картинки | по тексту, перерисовка, маска мышью, редактирование по инструкции (FLUX.2 klein, Qwen-Image-Edit) |
| Видео    | Wan: текст → видео и картинка → видео, просмотр и сохранение в MP4 |
| Речь     | диктовка с микрофона, расшифровка WAV, озвучка текста и сохранение в WAV |
| Модели   | готовые наборы, поиск по Hugging Face, скачивание **любых файлов любого репозитория** |

- **Видеокарта**: NVIDIA, AMD и Intel через Vulkan. Если Vulkan в системе нет, автоматически работает процессор.
- **Большие модели** картинок и видео состоят из нескольких файлов (диффузионная модель, VAE,
  текстовые энкодеры). Готовые наборы скачивают всё сразу и создают профиль; из своих файлов
  профиль собирается вручную («Модели» → «Картинки и видео» → «＋ Профиль»).
- **Мало видеопамяти** (6–8 ГБ)? Включите в настройках «держать веса в оперативной памяти» —
  медленнее, но большие модели запустятся.
- Модели и результаты лежат в `Документы\NeuroPocket`.

Ориентиры по видеопамяти: Z-Image Turbo ≈ 6 ГБ · FLUX.2 klein 4B ≈ 8 ГБ · FLUX.1 schnell ≈ 10 ГБ ·
Qwen-Image ≈ 16 ГБ · Wan 2.1 1.3B ≈ 8 ГБ · Wan 2.2 5B ≈ 12 ГБ · Wan 2.2 A14B ≈ 20 ГБ.
Чат-модели: 8B ≈ 6 ГБ, 14B ≈ 10 ГБ, 30B-A3B — работает даже частично на процессоре.

## Телефон (Android)

- **Чат** с потоковым ответом, кнопкой «Стоп», перегенерацией и сворачиванием «размышлений».
- **Фото и видео** в чате для моделей со зрением (Qwen2.5-VL, Gemma 3/4, SmolVLM2, InternVL) —
  модуль зрения `mmproj` скачивается вместе с моделью. Видео модель смотрит как серию кадров.
- **Картинки** (вкладка «Картинки»): по тексту, перерисовка фото, перерисовка закрашенной
  пальцем области, правка по инструкции (Instruct-Pix2Pix), кадр из видео, сохранение в галерею.
  Подходят однофайловые модели: SD 1.x/2.x, SD-Turbo, SDXS, SDXL. Описания — на английском.
- **Речь**: 🎤 в поле ввода — голосовой ввод, 🔊 у ответа — озвучка. Модели: «Модели» → «Речь».
- Скорость картинок на флагмане: SDXS — секунды, SD-Turbo — 10–30 с, SD 1.5 — 1–3 минуты.

| ОЗУ телефона | Чат-модели |
|--------------|------------|
| 3–4 ГБ       | Qwen 2.5 0.5B, Llama 3.2 1B, Gemma 3 1B (Q4_K_M) |
| 6 ГБ         | Qwen 3 1.7B, Gemma 4 E2B, Llama 3.2 3B |
| 8 ГБ и больше| Qwen 3 4B, Gemma 3 4B, модели 7–8B в Q4 |

## Речь

- Распознавание: **GigaAM v2** — лучшая для русского; **Whisper** (tiny…large-v3) — 99 языков.
- Голоса: русские Ирина, Денис, Дмитрий, Руслан (Piper); английские Amy, Lessac, Kokoro.
- Любая другая модель sherpa-onnx ставится по ссылке на архив `.tar.bz2`
  ([asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models),
  [tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)).

## Сборка вручную

**Android**: Android Studio (Narwhal+) с SDK 36, NDK и CMake 3.22.1 → открыть папку проекта → Run.
Из командной строки: `./gradlew assembleRelease`. Первая сборка долгая: CMake скачивает
llama.cpp и stable-diffusion.cpp, Gradle — библиотеку sherpa-onnx.

**Windows**: Visual Studio 2022, [Vulkan SDK](https://vulkan.lunarg.com/), JDK 21:

```bat
cmake -S app/src/main/cpp -B build-llm -DGGML_VULKAN=ON
cmake --build build-llm --config Release
cmake -S sdengine/src/main/cpp -B build-sd-vk -DSD_VULKAN=ON -DSD_JNI_NAME=sdengine_vk
cmake --build build-sd-vk --config Release --target sdengine
cmake -S sdengine/src/main/cpp -B build-sd-cpu -DSD_JNI_NAME=sdengine_cpu
cmake --build build-sd-cpu --config Release --target sdengine
rem все *.dll + DLL из sherpa-onnx-v1.13.8-win-x64-jni → desktop\resources\windows-x64
gradlew -p desktop packageMsi
```

Без интернета: положите исходники в `third_party/` (`llama.cpp`, `stable-diffusion.cpp`,
`sd-ggml` — копия ggml для stable-diffusion.cpp), CMake возьмёт их вместо загрузки.

## Устройство проекта

```
shared/                 общий Kotlin-код телефона и компьютера
├── llm/                LlamaEngine — чат и зрение (JNI к llama.cpp)
├── sd/                 SdEngine — картинки и видео (JNI к stable-diffusion.cpp)
├── hub/                Hugging Face: поиск, файлы репозиториев
└── speech/             каталог речевых моделей, загрузчик, распаковка, WAV
app/                    приложение для Android
├── src/main/cpp/       llm_jni.cpp + сборка llama.cpp (Android и Windows)
└── src/main/java/      экраны Compose, ViewModel, речь на Android
sdengine/               модуль stable-diffusion.cpp (своя копия ggml, Android и Windows)
desktop/                компьютерная версия (Compose Desktop)
```

## Ограничения

- Изменить существующее видео целиком нейросетью нельзя: открытые модели умеют создавать
  видео с нуля или из картинки, а не переделывать готовое. Можно взять кадр из видео и
  отредактировать его, а само видео — отправить в чат модели со зрением.
- На телефоне вычисления идут на процессоре; генерация видео и большие модели картинок там не запускаются.
- Не все форматы моделей поддерживаются: нужны GGUF (чат), safetensors/gguf/ckpt (картинки, видео)
  и ONNX-модели sherpa-onnx (речь). Модели PyTorch в формате diffusers по папкам нужно сначала сконвертировать.
- Модель картинок на телефоне собрана под ARMv8.2 (процессоры примерно с 2018 года).
