# Cirvue

Cirvue is a privacy-first alternative to Circle to Search for Android. Hold the power button, circle something on the screen, and Cirvue searches DuckDuckGo for it. Text recognition and image recognition run on the phone. Only the text query leaves the device.

## Features

- **Screen search.** Cirvue registers as the digital assistant. It receives a screenshot through the Android Assist API and shows it as an overlay.
- **Selection.** Draw around an object, drag a box, or tap a line of text.
- **Camera search.** Take a photo with the built-in CameraX viewfinder, or choose one with the system photo picker. Photos go through the same pipeline.
- **On-device recognition.** PP-OCRv6 reads text, brands, and model numbers. SmolVLM2-500M describes objects. Both run offline after you download them.
- **Careful queries.** Cirvue drops model numbers that the vision model produced but OCR did not see, so it does not invent product names.
- **DuckDuckGo search.** Results open in a Custom Tab. You can edit the query and search again. An optional setting lets you review every query before it is sent.

## Privacy

- No Google Play services, accounts, analytics, ads, or telemetry.
- Screenshots and photos stay in memory. Cirvue never writes them to disk and never uploads them.
- Only the text query goes to `https://duckduckgo.com/?q=…`, in your browser.
- Permissions: `CAMERA`, and `INTERNET` for the optional model downloads.
- No Accessibility Service, root, Shizuku, or hidden APIs. Apps that block screenshots stay blocked.

Two Android limits apply while Cirvue is the assistant:

- Android sends the assist data (text and layout) of the current app with each screenshot. Cirvue cannot turn this off, but it never reads the data.
- Android requires a speech recognizer with every assistant. Cirvue has no voice input, so apps that use the system speech recognizer get an error.

## Requirements

- Android 10 (API 29) or later.
- A 64-bit ARM device (`arm64-v8a`).
- About 600 MB free storage for both models, and 1.2 GB free memory for visual recognition.

## Set up

1. Install the app and open Cirvue.
2. Tap **Open settings**. Choose Cirvue as the **Digital assistant app**.
3. Download **Text recognition** (31 MB) and, if you want object recognition, **Visual recognition** (546 MB).
4. Hold the power button or the home handle to search your screen.

On some phones, holding the power button opens the power menu. Change this in the system settings for the power button.

## Models

| Model | Use | Size | License | Source |
|---|---|---|---|---|
| PP-OCRv6 small (ONNX) | Text detection and recognition | 31 MB | Apache-2.0 | [PaddlePaddle](https://huggingface.co/PaddlePaddle) |
| SmolVLM2-500M-Video-Instruct (Q8_0 GGUF) | Object description | 546 MB | Apache-2.0 | [ggml-org](https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF) |

Downloads use pinned Hugging Face revisions. Cirvue checks the SHA-256 of each file before it uses the file.

## Build

```sh
git clone --recursive <repository-url>
cd cirvue
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
```

You need JDK 21, the Android SDK (compileSdk 37), and NDK `29.0.14206865`. The build compiles llama.cpp, so the first build takes several minutes.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the modules, the recognition pipeline, platform findings, and measured performance. See [docs/DEVICE-TESTING.md](docs/DEVICE-TESTING.md) to test on an arm64 Linux host without a phone.

## License

Not chosen yet.
