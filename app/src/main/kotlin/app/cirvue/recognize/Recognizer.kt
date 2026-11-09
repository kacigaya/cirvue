package app.cirvue.recognize

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.scale
import app.cirvue.models.ModelPack
import app.cirvue.models.ModelStore
import app.cirvue.ocr.TextLine
import app.cirvue.ocr.TextRecognizer
import app.cirvue.search.Query
import app.cirvue.search.QueryBuilder
import app.cirvue.vlm.VisionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs OCR and the vision model on a crop and builds the query. Native models load on first
 * use and stay loaded until [release]. All native calls run on one thread, because neither
 * model is thread-safe.
 */
class Recognizer(private val models: ModelStore, private val threads: Int = defaultThreads()) {
    private val native = Dispatchers.Default.limitedParallelism(1)
    private var ocr: TextRecognizer? = null
    private var vlm: VisionModel? = null

    val hasText: Boolean get() = models.isInstalled(ModelPack.Text)
    val hasVision: Boolean get() = models.isInstalled(ModelPack.Vision)

    /**
     * Text regions of the whole image, so a tap can select a line. Empty without OCR. Detection
     * runs on a downscaled copy: full resolution takes seconds and would delay the first search.
     */
    suspend fun textBoxes(image: Bitmap): List<RectF> = withContext(native) {
        val ocr = textRecognizer() ?: return@withContext emptyList()
        val scale = (DETECT_MAX_SIDE.toFloat() / maxOf(image.width, image.height)).coerceAtMost(1f)
        if (scale == 1f) return@withContext ocr.detect(image)
        val small = image.scale((image.width * scale).toInt(), (image.height * scale).toInt())
        ocr.detect(small).map { RectF(it.left / scale, it.top / scale, it.right / scale, it.bottom / scale) }
    }

    /** Loads the models ahead of the first search, so the first result comes sooner. */
    suspend fun warmUp() = withContext(native) {
        textRecognizer()
        visionModel()
        Unit
    }

    suspend fun recognize(crop: Bitmap): Query = coroutineScope {
        val lines: List<TextLine> = withContext(native) { textRecognizer()?.recognize(crop).orEmpty() }
            .filter { it.confidence >= MIN_CONFIDENCE }
        val coverage = coverage(lines.map { it.box }, crop.width, crop.height)
        val texts = lines.map { it.text }
        val needsVision = texts.isEmpty() || coverage < QueryBuilder.TEXT_SELECTION_COVERAGE
        val description = if (needsVision) describe(crop, texts) else null
        QueryBuilder.build(texts, coverage, description)
    }

    private suspend fun describe(crop: Bitmap, texts: List<String>): String? = coroutineScope {
        // One task looks up and runs the model, so release() cannot close it in between.
        val running = AtomicReference<VisionModel?>()
        val job = async(native) {
            visionModel()?.let { model ->
                running.set(model)
                model.describe(crop, prompt(texts), MAX_TOKENS)
            }
        }
        try {
            job.await()
        } catch (e: CancellationException) {
            // The native loop checks this flag between tokens; the thread frees up right after.
            running.get()?.cancel()
            throw e
        }
    }

    suspend fun release() = withContext(native) {
        ocr?.close(); ocr = null
        vlm?.close(); vlm = null
    }

    private fun textRecognizer(): TextRecognizer? {
        if (ocr == null && hasText) {
            val f = { name: String -> models.file(ModelPack.Text, name) }
            ocr = TextRecognizer.open(f("det.onnx"), f("rec.onnx"), f("rec.yml"), threads)
        }
        return ocr
    }

    private fun visionModel(): VisionModel? {
        if (vlm == null && hasVision) {
            val f = { name: String -> models.file(ModelPack.Vision, name) }
            vlm = VisionModel.load(f("model.gguf"), f("mmproj.gguf"), threads)
        }
        return vlm
    }

    private companion object {
        const val MAX_TOKENS = 24

        /** Below this, PP-OCR output is mostly misread icons and textures. */
        const val MIN_CONFIDENCE = 0.5f

        /** Long side for tap-target detection. Screen text stays at least about 10 px tall. */
        const val DETECT_MAX_SIDE = 1440

        fun prompt(texts: List<String>): String = buildString {
            append("Write a short web search query that identifies the main object in this image. ")
            append("Use at most 8 words. Name a brand or model only if it is clearly readable. ")
            append("If you are not sure what it is, describe it in general words. ")
            if (texts.isNotEmpty()) {
                append("Text visible in the image: \"")
                append(texts.joinToString(" ").take(160))
                append("\". ")
            }
            append("Output only the query.")
        }

        fun coverage(boxes: List<RectF>, width: Int, height: Int): Float {
            if (width <= 0 || height <= 0) return 0f
            val area = boxes.sumOf { (it.width() * it.height()).toDouble() }
            return (area / (width.toDouble() * height)).toFloat().coerceIn(0f, 1f)
        }

        /** Big cores only; using every core makes small cores the bottleneck. */
        fun defaultThreads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    }
}
