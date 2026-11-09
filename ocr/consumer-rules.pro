# ONNX Runtime creates these Java objects from native code by name. R8 cannot see those
# calls, and the AAR ships no keep rules for them.
-keep class ai.onnxruntime.** { *; }
