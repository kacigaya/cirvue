# Cirvue

Privacy-first Android alternative to Circle to Search. Kotlin, Jetpack Compose, Material 3 Expressive. Read `docs/ARCHITECTURE.md` before you change code.

## Rules

- No Google Play services, accounts, analytics, ads, or telemetry.
- Never upload images. Only the generated text query leaves the device, and only to DuckDuckGo.
- Do not store screenshots or photos on disk. Keep them in memory and drop them when the screen closes.
- No Accessibility Service, root, Shizuku, or hidden APIs. Respect `FLAG_SECURE` (a null screenshot).
- Ask before you add a dependency.
- UI and UX design decisions belong to the maintainer. Follow `docs/ARCHITECTURE.md` § UI.

## Modules

- `:app`: assistant session, camera, selection overlay, recognition pipeline, search, settings, model downloads.
- `:ocr`: PP-OCRv6 (small) text detection and recognition on ONNX Runtime. Pure Kotlin pre- and post-processing, no OpenCV.
- `:vlm`: SmolVLM2-500M on llama.cpp (`libmtmd`) through JNI. llama.cpp is a Git submodule at `vlm/src/main/cpp/llama.cpp`, pinned to `v0.9.0`.

## Toolchain

- JDK 21, Gradle 9.9 wrapper, AGP 9.5.1 (built-in Kotlin 2.4.21), compileSdk 37, minSdk 29, targetSdk 36.
- NDK `29.0.14206865`. Only `arm64-v8a` is built.
- Clone with `git clone --recursive`, or run `git submodule update --init`.

### Host-specific setup (this aarch64 Linux host only)

The NDK and SDK CMake ship x86_64 binaries only. On this host:

- The NDK `toolchains/llvm/prebuilt/linux-x86_64/bin` tools are replaced with Ubuntu LLVM 20 (`clang-20`, `lld-20`). `clang-21` is a shell wrapper that adds the NDK sysroot and a resource directory at `~/android-sdk/ndk-host-shim/resource`. The original binaries are in `bin.x86_64/`.
- `local.properties` sets `cmake.dir=/usr` (Ubuntu CMake 3.28 and `ninja-build`).
- `~/.gradle/gradle.properties` points `aapt2` to a native arm64 build.

Do not commit host-specific paths.

## Commands

```sh
./gradlew :app:assembleDebug          # build the APK
./gradlew testDebugUnitTest           # JVM unit tests, all modules
./gradlew :app:lintDebug              # Android lint
```

## Device tests

Use the redroid container `velin-android` (Android 15, arm64, no camera). See `docs/DEVICE-TESTING.md`.

```sh
export QEMU_LD_PREFIX=$HOME/android-sdk/x86_64-root
adb connect 127.0.0.1:5555
adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Test models are in `~/cirvue-models` (`ocr/det.onnx`, `ocr/rec.onnx`, `ocr/rec.yml`, `vlm/model.gguf`, `vlm/mmproj.gguf`).

## Git

- Small, logical commits in Conventional Commits format.
- No agent, AI, or co-author attribution anywhere.
