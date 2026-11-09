# OCR

`TextRecognizer` runs PP-OCRv6 small detection and recognition on the caller's thread.
Use one caller at a time. Close the recognizer when it is no longer needed.
The caller owns the input bitmap. All crops and tensors stay in memory.

Detection uses the SDK's resize defaults: `limit_side_len=64`, `limit_type=min`,
and `max_side_limit=4000`. Dimensions round to multiples of 32 with half-even
rounding. Input uses BGR, ImageNet mean and standard deviation, and CHW layout.
DB uses `thresh=0.2`, `box_thresh=0.45`, `unclip_ratio=1.4`, and at most 3000
8-connected components. Fast scoring averages the probability inside each box.

Boxes are axis-aligned. This replaces the SDK's rotated contours and polygon
expansion. Rotated text and nearby diagonal lines can merge or read poorly.
Unclip expands each box by `area * ratio / perimeter`. Reading order uses a
10-source-pixel row tolerance. There is no orientation classifier.

Recognition uses RGB, height 48, aspect ratio resize, and normalized zero padding
to at least width 320. Width is capped at 3200, as in the SDK. Very long lines
can lose detail. Each crop runs separately. CTC uses the config dictionary,
blank at index 0, and a final space. Confidence is the mean probability of
retained characters. Empty decoded lines are omitted.

The dictionary reader accepts Paddle's block list of plain, single-quoted, or
double-quoted string scalars. It preserves Unicode and quoted spaces. It is not
a general YAML parser. Unsupported scalar forms fail at load time.

`OcrMath.kt` adapts the PaddleOCR Android SDK algorithms under Apache-2.0.
Bilinear resize runs on integer pixels without OpenCV. Integer rounding can
differ slightly from OpenCV's fixed-point implementation.

## Verification

1. Run `./gradlew :ocr:testDebugUnitTest`.
2. Set `QEMU_LD_PREFIX=$HOME/android-sdk/x86_64-root` on this host.
3. Connect with `~/android-sdk/platform-tools/adb connect 127.0.0.1:5555`.
4. Push `~/cirvue-models/ocr` to `/data/local/tmp/cirvue-ocr-test`.
5. Run `./gradlew :ocr:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.modelDir=/data/local/tmp/cirvue-ocr-test`.
6. Read logcat tag `CirvueOcrTest` for model metadata, text, confidence, boxes,
   and elapsed milliseconds. `recognizeMs` includes detection.
7. Remove `/data/local/tmp/cirvue-ocr-test` from the device.

The device test requires external model files. It renders synthetic text in
memory at 1080x2400 and 820x140. It writes no image files. Timing uses four
intra-op threads and includes first-run costs; it is not a phone benchmark.
Android test libraries are used only by the test APK.
