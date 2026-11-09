package app.cirvue.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import java.io.File
import java.nio.FloatBuffer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlin.math.ceil
import kotlin.math.floor

/** One line of text found in an image. [box] is the axis-aligned bound in source bitmap pixels. */
data class TextLine(val text: String, val confidence: Float, val box: RectF)

/**
 * PP-OCRv6 text detection and recognition on ONNX Runtime. Not thread-safe: use one caller
 * at a time. All calls block; run them off the main thread.
 */
class TextRecognizer private constructor(
    private val environment: OrtEnvironment,
    private val detector: OrtSession,
    private val reader: OrtSession,
    private val dictionary: List<String>,
) : AutoCloseable {
    private var closed = false

    private fun pixels(bitmap: Bitmap): IntArray {
        require(!bitmap.isRecycled) { "Bitmap is recycled" }
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) {
            requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) { "Cannot read hardware bitmap" }
        } else bitmap
        try {
            return IntArray(readable.width * readable.height).also {
                readable.getPixels(it, 0, readable.width, 0, 0, readable.width, readable.height)
            }
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    private fun <T> infer(session: OrtSession, data: FloatArray, size: Size,
                          consume: (FloatArray, LongArray) -> T): T {
        val shape = longArrayOf(1, 3, size.height.toLong(), size.width.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(session.inputNames.single() to input)).use { output ->
                val tensor = output.get(session.outputNames.single()).orElseThrow() as? OnnxTensor
                    ?: error("OCR output must be a tensor")
                val buffer = tensor.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                return consume(values, tensor.info.shape)
            }
        }
    }


    /** Finds text regions only. Faster than [recognize]; used to make text tappable. */
    fun detect(bitmap: Bitmap): List<RectF> {
        check(!closed) { "TextRecognizer is closed" }
        val size = detectionSize(bitmap.width, bitmap.height)
        val data = imageTensor(pixels(bitmap), bitmap.width, bitmap.height, size, detection = true)
        return infer(detector, data, size) { values, shape ->
            require(shape.size == 4 && shape[0] == 1L && shape[1] == 1L &&
                shape[2] in 1..Int.MAX_VALUE.toLong() && shape[3] in 1..Int.MAX_VALUE.toLong()) {
                "Expected detection output [1, 1, H, W], got ${shape.contentToString()}"
            }
            dbBoxes(values, shape[3].toInt(), shape[2].toInt(), bitmap.width, bitmap.height)
                .map { RectF(it.left, it.top, it.right, it.bottom) }
        }
    }

    /** Finds and reads text lines, sorted top-to-bottom, then left-to-right. */
    fun recognize(bitmap: Bitmap): List<TextLine> = detect(bitmap).mapNotNull { box ->
        val left = floor(box.left).toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(box.top).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(box.right).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(box.bottom).toInt().coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        try {
            val resized = recognitionSize(crop.width, crop.height)
            val size = Size(maxOf(320, resized.width), 48)
            val data = imageTensor(pixels(crop), crop.width, crop.height, resized,
                detection = false, paddedWidth = size.width)
            val (text, confidence) = infer(reader, data, size) { values, shape ->
                require(shape.size == 3 && shape[0] == 1L && shape[2] == dictionary.size.toLong() &&
                    shape[1] in 1..Int.MAX_VALUE.toLong()) {
                    "Recognition output and dictionary differ: ${shape.contentToString()}, ${dictionary.size} entries"
                }
                decodeCtc(values, shape[1].toInt(), dictionary)
            }
            if (text.isBlank()) null else TextLine(text, confidence, box)
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { detector.close() } finally { reader.close() }
        // OrtEnvironment is shared by ORT in this process. Do not close it here.
    }

    companion object {
        /** Loads the detection model, recognition model, and recognition config (`inference.yml`). */
        fun open(detModel: File, recModel: File, recConfig: File, threads: Int = 4): TextRecognizer {
            require(threads > 0) { "threads must be positive" }
            val dictionary = parseDictionary(recConfig.readText())
            val environment = OrtEnvironment.getEnvironment()
            fun session(file: File): OrtSession = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(threads)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                environment.createSession(file.absolutePath, options).also { session ->
                    try {
                        require(session.inputNames.size == 1 && session.outputNames.size == 1) {
                            "OCR model must have one input and one output"
                        }
                        val input = session.inputInfo.values.single().info as? TensorInfo
                            ?: error("OCR input must be a tensor")
                        val output = session.outputInfo.values.single().info as? TensorInfo
                            ?: error("OCR output must be a tensor")
                        require(input.type == OnnxJavaType.FLOAT && input.shape.size == 4 &&
                            (input.shape[0] == 1L || input.shape[0] < 0) && input.shape[1] == 3L &&
                            output.type == OnnxJavaType.FLOAT) { "Unsupported OCR tensor type or shape" }
                    } catch (error: Throwable) {
                        session.close()
                        throw error
                    }
                }
            }
            val detector = session(detModel)
            try {
                return TextRecognizer(environment, detector, session(recModel), dictionary)
            } catch (error: Throwable) {
                detector.close()
                throw error
            }
        }
    }
}
