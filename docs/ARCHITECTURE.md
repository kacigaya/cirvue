# Architecture

Cirvue turns a selected part of the screen, or of a photo, into a short text query. It then opens that query on DuckDuckGo. All image work runs on the device.

## Flow

```text
 Hold power ──► CirvueInteractionService ──► CirvueSession.onHandleScreenshot(bitmap)
                                                   │ (null if the app uses FLAG_SECURE)
 Launcher / shortcut ──► CameraScreen ──► photo ───┤
                                                   ▼
                                    CaptureHolder (in memory, one image)
                                                   ▼
                     SearchActivity: SelectionOverlay  (lasso, box, or tap)
                                                   ▼  crop
                     Recognizer: TextRecognizer (:ocr)  +  VisionModel (:vlm)
                                                   ▼
                     QueryBuilder ──► review (optional) ──► DuckDuckGo URL ──► Custom Tab
```

## Feasibility findings

| Area | Finding | Source |
|---|---|---|
| Assistant | A `VoiceInteractionService` needs `BIND_VOICE_INTERACTION`, `sessionService`, and `recognitionService` in its XML. AOSP rejects the service without a recognition service, so Cirvue ships a stub that returns an error. | AOSP `VoiceInteractionServiceInfo` |
| Assistant role | `ROLE_ASSISTANT` is not requestable through the role dialog. The app sends the user to the default-apps settings and checks `RoleManager.isRoleHeld`. | `RoleManager` docs |
| Screenshot | `VoiceInteractionSession.onHandleScreenshot` gives the screenshot when the user allows "Use screenshot". It is `null` for secure windows or when the user turns it off. | `VoiceInteractionSession` docs |
| Assist data | Cirvue does not need the view structure. The service calls `setDisabledShowContext(SHOW_WITH_ASSIST)`, so the system does not collect it. | `VoiceInteractionService` docs |
| Camera | CameraX 1.6.2 (stable) has `CameraXViewfinder` in `camera-compose`. `ImageCapture.takePicture` with an in-memory callback avoids files. | CameraX release notes |
| OCR | PaddleOCR 3.x deploys on Android with ONNX Runtime and PP-OCRv6. Its sample uses OpenCV; Cirvue ports the small pre- and post-processing steps to Kotlin to avoid OpenCV. PP-OCRv6 small: 9.9 MB detection, 21.2 MB recognition, 50 languages. | PaddleOCR Android deployment docs |
| VLM | llama.cpp `libmtmd` supports SmolVLM2. `ggml-org/SmolVLM2-500M-Video-Instruct-GGUF` Q8_0: 437 MB model and 109 MB projector. | llama.cpp `docs/multimodal.md`, Hugging Face |
| Search | `https://duckduckgo.com/?q=<query>`. Cirvue adds no other parameters, so user settings and DuckDuckGo branding stay intact. | DuckDuckGo URL parameters help page |

## Modules

### `:app`

| Package | Responsibility |
|---|---|
| `assist` | `CirvueInteractionService`, `CirvueSessionService`, `CirvueSession`, `RecognitionStub`. The session takes the screenshot, puts it in `CaptureHolder`, starts `SearchActivity`, and hides itself. |
| `capture` | `CaptureHolder`: one in-memory bitmap handed from the session or camera to `SearchActivity`. Cleared when the activity finishes. |
| `camera` | `CameraScreen`: `CameraXViewfinder`, shutter, photo picker. |
| `select` | `SelectionOverlay` and pure geometry (`Selection`): lasso path, box, tap target, crop rectangle. |
| `recognize` | `Recognizer`: runs OCR and the VLM on the crop, then `QueryBuilder`. |
| `search` | `QueryBuilder` (pure, unit-tested), `DuckDuckGo` URL, Custom Tabs launch. |
| `models` | `ModelCatalog` (pinned URLs and SHA-256), `ModelStore` (`DownloadManager`, verification, delete). |
| `settings` | `Settings`: `SharedPreferences` with `StateFlow`. One setting today: review the query before search. |
| `ui` | Theme, icons, `MainActivity` home screen. |

### `:ocr`

`TextRecognizer.open(det, rec, recConfig)`, `detect(bitmap)`, `recognize(bitmap)`. DB detection, axis-aligned boxes after unclip, CTC decoding with the dictionary from `inference.yml`.

### `:vlm`

`VisionModel.load(model, mmproj)`, `describe(bitmap, prompt, maxTokens)`, `cancel()`. A small JNI layer over `llama.h`, `mtmd.h`, and `mtmd-helper.h`. Greedy decoding. The chat template comes from the GGUF file.

## Query generation

1. Crop the selection bounds from the source image. Downscale the crop to at most 512 px on the long side for the VLM, so it fits one image tile.
2. Run OCR on the crop.
3. If text covers most of the crop, the query is the text.
4. Otherwise ask the VLM for a short query and give it the OCR text as context.
5. `QueryBuilder` cleans the result: it removes quotes, prompts, and filler, limits length, and drops model numbers or brand-like tokens that the VLM produced but OCR did not see. This stops invented product names.
6. If no model is installed, the user types the query.

## Privacy

- The app has `INTERNET` only for model downloads through `DownloadManager`. Search opens in the user's browser through Custom Tabs.
- Downloads come from pinned Hugging Face revisions and are checked with SHA-256 before use.
- Images live in memory only. Nothing is written to disk, logged, or shared.

## UI

Material 3 Expressive with dynamic color, edge-to-edge, `MotionScheme.expressive()`.

**Home (`MainActivity`)**: large top app bar "Cirvue".
1. Assistant card. It shows the state of the assistant role. When Cirvue is not the assistant, a filled button opens the default-apps settings.
2. A large filled button "Search with camera".
3. Models section. One list item per pack ("Text recognition", "Visual recognition"), with size, state, and a trailing download, cancel, or delete action. Progress shows as a linear indicator.
4. Settings section. A switch "Review query before searching".
5. A short privacy line at the bottom.

**Search overlay (`SearchActivity`)**: the image fills the screen, edge to edge.
1. On enter, a scrim fades in and a soft gradient glow sweeps the screen edges.
2. A hint pill at the top: "Circle, box, or tap what you want to search".
3. A floating toolbar at the bottom: a connected toggle (Draw / Box), a camera button, and a close button.
4. Drawing leaves a glowing stroke. On release, the selection animates to a rounded rectangle and the area outside it dims more.
5. A bottom panel slides up. It shows a loading indicator while recognition runs, then a search field with the query, a Search button, and up to three suggestion chips (OCR lines).
6. If review is off, the search opens when the query is ready. The panel stays, so the user can edit and search again when they come back.
7. Errors (no screenshot, no models, recognition failed) show in the same panel with one clear action.

**Camera (`CameraScreen`)**: full-screen viewfinder, a close button at the top start, and a bottom row with the photo picker, the shutter, and an empty slot for balance. If no camera exists, the screen shows a message and the photo picker.

## Limits

- 64-bit ARM only.
- Text boxes are axis-aligned. Strongly rotated text reads less well.
- Tap selects the text line under the finger. If there is none, it selects a square around the tap point. There is no object segmentation model.
