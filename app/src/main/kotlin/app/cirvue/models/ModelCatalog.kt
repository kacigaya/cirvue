package app.cirvue.models

/** One downloadable file, pinned to a repository revision and checked by SHA-256. */
data class ModelFile(val name: String, val url: String, val sha256: String, val size: Long)

/** A model pack is installed when all of its files are present under `files/models/<dir>`. */
enum class ModelPack(val dir: String, val files: List<ModelFile>) {
    /** PP-OCRv6 small, Apache-2.0. */
    Text(
        dir = "ocr",
        files = listOf(
            ModelFile("det.onnx", hf(OCR_DET, "inference.onnx"), "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e", 9_880_512),
            ModelFile("rec.onnx", hf(OCR_REC, "inference.onnx"), "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634", 21_159_378),
            ModelFile("rec.yml", hf(OCR_REC, "inference.yml"), "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1", 150_579),
        ),
    ),

    /** SmolVLM2-500M-Video-Instruct, Q8_0 GGUF, Apache-2.0. */
    Vision(
        dir = "vlm",
        files = listOf(
            ModelFile("model.gguf", hf(VLM, "SmolVLM2-500M-Video-Instruct-Q8_0.gguf"), "6f67b8036b2469fcd71728702720c6b51aebd759b78137a8120733b4d66438bc", 436_808_704),
            ModelFile("mmproj.gguf", hf(VLM, "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf"), "921dc7e259f308e5b027111fa185efcbf33db13f6e35749ddf7f5cdb60ef520b", 108_785_184),
        ),
    );

    val size: Long get() = files.sumOf { it.size }
}

private const val OCR_DET = "PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62"
private const val OCR_REC = "PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/b8f84f0b80c529de40b4fbb3544b84fa7233a513"
private const val VLM = "ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/ccd7aae53bcb1997355c2f094959e72b3642ce17"

private fun hf(repoRevision: String, path: String) = "https://huggingface.co/$repoRevision/$path"
