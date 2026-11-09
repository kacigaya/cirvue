# Visual recognition

`VisionModel` runs a GGUF language model and its vision projector on the CPU.
Call `describe` and `close` from one worker thread. Another thread can call
`cancel`. Cancellation returns the text generated so far. It can interrupt
language model evaluation. The vision encoder has no abort callback in this
version of libmtmd, so cancellation can wait for image encoding to finish.

Each call uses the chat template stored in the language model. It clears model
memory before and after inference. Images and generated text stay in memory.
The wrapper accepts software bitmaps and copies other bitmap formats to
ARGB_8888. It scales images down to 512 px on the long side, so the encoder runs one tile.
On redroid this cut describe time from 11.1 s (768 px) to 4.4 s. Long prompts or large images can exceed the 4096-token context. That
case throws an exception before evaluation.

The native build uses optimized code for debug builds too. It builds the same
Android CPU variants as the pinned llama.cpp Android example. At load time,
each variant's baseline feature probe gets a score. Only the best compatible
variant initializes. Libraries load by name so APK library extraction is not
required. No GPU backend or downloaded build dependency is used.

## Device benchmark

1. Build the library with `./gradlew :vlm:assembleDebug`.
2. In its generated CMake build directory under `vlm/.cxx/Debug`, run
   `cmake -DCIRVUE_VLM_BENCHMARK=ON .`.
3. Run `cmake --build . --target cirvue_vlm_benchmark`.
4. Push the executable, generated `.so` files, NDK `libc++_shared.so`, model,
   projector, and a packed RGB image to a temporary device directory.
5. Run `LD_LIBRARY_PATH=<directory> <directory>/cirvue_vlm_benchmark <model>
   <projector> <rgb-image> <width> <height>` through adb.
6. Remove the temporary device directory after the test.

The executable compiles the same inference code as JNI. It reports model load
time, an isolated image encode, prompt evaluation, generation speed, describe
latency, peak process RSS, and output. The isolated encode happens before the
measured describe call. Thus describe uses a warm encoder. Prompt evaluation
includes image encoding and text evaluation. Describe latency excludes model
loading and the isolated probe. The input must contain exactly three RGB bytes
per pixel. This harness reads test data from disk; the Android library does not.
