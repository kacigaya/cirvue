# Architecture

Cirvue turns a selected part of the screen, or of a photo, into a short text query. It then opens that query on DuckDuckGo. All image work runs on the device. An image leaves Cirvue only when the user shares it with another app.

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
                     QueryBuilder ──► review (optional, or forced for private text)
                                                   ▼
                     Result panel ──► Web, Images, or Shopping ──► DuckDuckGo URL ──► Custom Tab
                                  └─► Search by image ──► Android share sheet ──► app the user picks
```

## Feasibility findings

| Area | Finding | Source |
|---|---|---|
| Assistant | A `VoiceInteractionService` needs `BIND_VOICE_INTERACTION`, `sessionService`, and `recognitionService` in its XML. AOSP rejects the service without a recognition service, so Cirvue ships a stub that returns an error. | AOSP `VoiceInteractionServiceInfo` |
| Assistant role | `ROLE_ASSISTANT` is not requestable through the role dialog. The app sends the user to the default-apps settings and checks `RoleManager.isRoleHeld`. | `RoleManager` docs |
| Screenshot | `VoiceInteractionSession.onHandleScreenshot` gives the screenshot. It can arrive before `onShow`, so each invocation starts in `onPrepareShow`. It is `null` for secure windows. | `VoiceInteractionSession` docs, tested on Android 15 |
| Assist data | Android takes the screenshot only when it also fetches the assist data of the current app. With `setDisabledShowContext(SHOW_WITH_ASSIST)`, or with "Use text from screen" off, the screenshot is always `null`. Cirvue keeps assist data on and never reads it: the session does not override `onHandleAssist`. | AOSP `AssistDataRequester`, tested on Android 15 |
| Camera | CameraX 1.6.2 (stable) has `CameraXViewfinder` in `camera-compose`. `ImageCapture.takePicture` with an in-memory callback avoids files. | CameraX release notes |
| OCR | PaddleOCR 3.x deploys on Android with ONNX Runtime and PP-OCRv6. Its sample uses OpenCV; Cirvue ports the small pre- and post-processing steps to Kotlin to avoid OpenCV. PP-OCRv6 small: 9.9 MB detection, 21.2 MB recognition, 50 languages. | PaddleOCR Android deployment docs |
| ONNX Runtime | Android releases 1.29 and later ship Microsoft 1DS telemetry: an auto-start `ContentProvider`, `ACCESS_NETWORK_STATE`, and device IDs. Cirvue pins 1.28.0, which has none of these. The manifest also removes the provider and the permission. | AAR contents of 1.27 to 1.31 |
| VLM | llama.cpp `libmtmd` supports SmolVLM2 and Gemma 4. `ggml-org/SmolVLM2-500M-Video-Instruct-GGUF` Q8_0: 437 MB model and 109 MB projector. `ggml-org/gemma-4-E2B-it-GGUF` Q4_0: 2.84 GB model and 557 MB projector, Apache-2.0, not gated. | llama.cpp `docs/multimodal.md`, Hugging Face |
| Gemma 4 prompt | The Gemma 4 chat template needs a Jinja engine. `llama_chat_apply_template` formats only templates it knows by name, and the wrapper does not build the Jinja engine. The wrapper writes the single user turn itself: `<\|turn>user`, the content, `<turn\|>`, then `<\|turn>model`. | Template in the GGUF file, tested on redroid |
| Gemma 4 image size | Gemma 4 accepts 70, 140, 280, 560, or 1120 tokens for one image. `mtmd_context_params.image_max_tokens` sets the limit. | Gemma 4 model card, `mtmd.h` |
| Search | `https://duckduckgo.com/?q=<query>`. Cirvue adds no setting parameters, so user settings and DuckDuckGo branding stay intact. `&ia=images&iax=images` and `&ia=shopping&iax=shopping` open the Images and Shopping tabs. DuckDuckGo uses these two parameters in its own tab links but does not document them. | DuckDuckGo URL parameters help page, tested in a browser |
| Search results | DuckDuckGo has no API for full results, and it does not allow its results inside another app. A browser with an address bar is allowed. Cirvue shows what it recognized in its own panel and opens the results in a Custom Tab. | DuckDuckGo partnership guidelines |
| Image share | `ContentProvider.openPipeHelper` serves bytes from memory through a pipe. `ACTION_SEND` with `FLAG_GRANT_READ_URI_PERMISSION` gives one app read access to one URI. `EXTRA_AUTO_LAUNCH_SINGLE_CHOICE` set to false keeps the share sheet visible when only one app can take the image. | `ContentProvider` and `Intent` docs, tested on Android 15 |

## Modules

### `:app`

| Package | Responsibility |
|---|---|
| `assist` | `CirvueInteractionService`, `CirvueSessionService`, `CirvueSession`, `RecognitionStub`. The session takes the screenshot, puts it in `CaptureHolder`, starts `SearchActivity`, and hides itself. |
| `capture` | `CaptureHolder`: one in-memory bitmap handed from the session or camera to `SearchActivity`. Cleared when the activity finishes. |
| `camera` | `CameraScreen`: `CameraXViewfinder`, shutter, photo picker. |
| `select` | `SelectionOverlay` and pure geometry (`Selection`): lasso path, box, tap target, crop rectangle. |
| `recognize` | `Recognizer`: runs OCR and the best installed VLM on the crop, then `QueryBuilder`. |
| `search` | `QueryBuilder` and `Gtin` (pure, unit-tested), `DuckDuckGo` URL for the Web, Images, and Shopping tabs, Custom Tabs launch. |
| `share` | `SharedCrop` and `CropProvider`: hold the one shared image in memory and serve it to the app the user picked. |
| `models` | `ModelCatalog` (pinned URLs and SHA-256, three packs), `ModelStore` (`DownloadManager`, verification, delete). |
| `settings` | `Settings`: `SharedPreferences` with `StateFlow`. One setting today: review the query before search. |
| `ui` | Theme, icons, `MainActivity` home screen. |

### `:ocr`

`TextRecognizer.open(det, rec, recConfig)`, `detect(bitmap)`, `recognize(bitmap)`. DB detection, axis-aligned boxes after unclip, CTC decoding with the dictionary from `inference.yml`.

### `:vlm`

`VisionModel.load(model, mmproj, threads, maxSide, imageTokens)`, `describe(bitmap, prompt, maxTokens)`, `cancel()`. A small JNI layer over `llama.h`, `mtmd.h`, and `mtmd-helper.h`. Greedy decoding. The chat template comes from the GGUF file, except for Gemma 4 (see Feasibility findings).

## Vision packs

| Pack | Model | Use |
|---|---|---|
| Lite | SmolVLM2-500M | Describes an object in general words. About 1 GB of memory. |
| Full | Gemma 4 E2B | Names products, brands, places, and species. About 2.1 GB of private memory and a 2.7 GB mapped file. |

1. `Recognizer` uses Full when it is installed, and Lite if not.
2. The home screen gives no download button for Full when Android reports less than 6.5 GiB of memory (`ModelPack.FULL_MIN_MEMORY_BYTES`). Phones sold with 8 GB report about 7.3 GiB.
3. Full gets a 1024 px image and 140 image tokens. Lite gets a 512 px image, which is one tile.

## Query generation

1. Crop the selection bounds from the source image.
2. Run OCR on the crop. Drop lines with confidence below 0.5, and lines of 3 characters or fewer when longer lines exist. Icons often read as short stray strings.
3. If text covers at least 30% of the crop, the query is the text.
4. Otherwise ask the VLM and give it the OCR text as context. Lite gets one instruction and answers with a query. Full answers with four lines: `type`, `brand`, `name`, and `query`. A value of `unknown` counts as empty.
5. `QueryBuilder` cleans the query: it removes quotes, prompts, and filler, limits it to 8 words, and drops tokens with letters and digits (model numbers) that the VLM produced but OCR did not see. This stops invented product names. The answer before this check becomes the first suggestion, so the user can still pick it.
6. `QueryBuilder` also returns what the VLM saw: the type and the name. The name is confirmed only when OCR read all of its words, apart from words that repeat the type. If not, the panel shows "Could be" before the name.
7. `Gtin` looks for a product code in the OCR lines: 12 or 13 digits with a correct check digit. OCR reads the long guard bars of a barcode as quotes or bars, so those can stand between the digits. If the code is at least half of the text, the code is the query. If not, it is the first suggestion.
8. If no model is installed, the user types the query.

## Review

The setting "Review query before searching" is off by default, so a query opens DuckDuckGo immediately. `QueryBuilder` sets `Query.review` to stop this for a query that can hold private details:

- It contains an email address.
- It contains 7 or more digits in a row, with or without spaces, dots, hyphens, slashes, or parentheses. Phone, card, and account numbers have this form. A product code has it too, so a product code always waits for the user.
- It comes from screen text and has more than 12 words. That is a sentence or a message, not a name.

The panel then shows the query and a notice. Nothing leaves the device until the user taps Search.

## Privacy

- The app has `INTERNET` only for model downloads through `DownloadManager`. Search opens in the user's browser through Custom Tabs. That browser sees the query too.
- Downloads come from pinned Hugging Face revisions and are checked with SHA-256 before use.
- Images live in memory only. Nothing is written to disk or logged.
- Cirvue uploads no image. "Search by image" opens the Android share sheet with the selected crop, and the user picks the app that gets it. That app then follows its own privacy policy.
- The shared crop is a JPEG in memory. `CropProvider` is not exported and serves it through a pipe. Each share has a new random URI, and the read grant covers only that URI. Cirvue drops the crop when the search screen closes or a new search starts.

## Measured performance

Redroid (Android 15) on an Ampere Neoverse-N1 host, 4 threads. Phone results will differ.

| Step | Time |
|---|---|
| OCR, 820 x 140 crop (detect and read) | 0.45 s |
| OCR detection, full 1080 x 2400 screenshot | 2.9 s (tap targets use a 1440 px copy) |
| SmolVLM2 load | 0.8 s to 3.7 s |
| SmolVLM2 describe, 512 px image | 4.4 s |
| SmolVLM2 describe, 768 px image | 11.1 s |
| SmolVLM2 peak memory | 1.0 GB RSS (0.6 GB private, 0.4 GB mapped file) |
| Gemma 4 E2B load, files in the page cache | 2.6 s |
| Gemma 4 E2B describe, 70 image tokens | 4.8 s |
| Gemma 4 E2B describe, 140 image tokens | 6.1 s to 8.2 s in ten runs, 10.5 s in one |
| Gemma 4 E2B describe, 280 image tokens | 13.9 s |
| Gemma 4 E2B peak memory | 5.0 GB RSS (2.1 GB private, 2.7 GB mapped file) |

The Gemma 4 values come from the benchmark executable with the four-line prompt. In the app on this host, `Recognizer` uses 2 threads, and software rendering uses the other cores. One search then takes about 23 s.

On six test photos, Gemma 4 E2B named a game console, a plant species, and a landmark with 70 tokens. It needed 140 tokens to read a brand logo on a shoe. SmolVLM2 gave a wrong name for the console and the plant. This is a spot check, not a benchmark.

## UI

Material 3 Expressive with dynamic color, edge-to-edge, `MotionScheme.expressive()`.

**Home (`MainActivity`)**: large top app bar "Cirvue".
1. Assistant card. It shows the state of the assistant role. When Cirvue is not the assistant, a filled button opens the default-apps settings.
2. A large filled button "Search with camera".
3. Models section. One list item per pack ("Text recognition", "Visual recognition · Lite", "Visual recognition · Full"), with size, state, and a trailing download, cancel, or delete action. Progress shows as a linear indicator. On a phone with too little memory, the Full item shows a message in place of the download button.
4. Settings section. A switch "Review query before searching".
5. A short privacy line at the bottom.

**Search overlay (`SearchActivity`)**: the image fills the screen, edge to edge.
1. On enter, a scrim fades in and a soft gradient glow sweeps the screen edges.
2. A hint pill at the top: "Circle, box, or tap what you want to search".
3. A floating toolbar at the bottom: a connected toggle (Draw / Box), a camera button, and a close button.
4. Drawing leaves a glowing stroke. On release, the selection animates to a rounded rectangle and the area outside it dims more.
5. A bottom panel slides up. It shows a loading indicator while recognition runs. Then it shows, from top to bottom:
   1. What Cirvue recognized: the type as a title and the name below it, or "Product code". A text selection has no title.
   2. A notice with a shield icon when the user must check the query.
   3. A search field with the query, and a filled Search button.
   4. Assist chips with icons: Images, Shopping, and Search by image. They wrap to a second line on a narrow screen. Images and Shopping need a query. Search by image shows when a crop exists.
   5. Up to three suggestion chips in a row that scrolls.
6. If review is off and the query does not need a check, the search opens when the query is ready. The panel stays, so the user can edit and search again when they come back.
7. Errors (no screenshot, no models, recognition failed) show in the same panel with one clear action.

**Camera (`CameraScreen`)**: full-screen viewfinder, a close button at the top start, and a bottom row with the photo picker, the shutter, and an empty slot for balance. If no camera exists, the screen shows a message and the photo picker.

## Limits

- 64-bit ARM only.
- Text boxes are axis-aligned. Strongly rotated text reads less well.
- Tap selects the text line under the finger. If there is none, it selects a square around the tap point. There is no object segmentation model.
- Android requires a speech recognizer with every assistant. While Cirvue is the assistant, apps that use the system speech recognizer get an error.
- Android sends the assist data of the current app with each screenshot. Cirvue cannot turn this off, but it never reads the data.
- If no browser supports Custom Tabs, results open in the default browser.
- The name from the VLM can be wrong. The panel says "Could be" when no text in the image confirms it.
- Cirvue cannot find visually similar products or copies of an image by itself. Those searches need the image. "Search by image" gives it to an app the user picks.
- Product codes come from the printed digits, not from the bars. Cirvue reads no QR codes and no 8-digit codes.
- The Images and Shopping tab parameters are not documented by DuckDuckGo. If they stop working, the web results open.
- The Full vision pack loads the audio encoder of Gemma 4 too, because libmtmd has no option to skip it. This is part of its memory use. Patch libmtmd only if memory on 8 GB phones proves too small.
- The shared image comes through a pipe. An app that needs a seekable file cannot read it.
