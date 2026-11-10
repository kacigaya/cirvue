package app.cirvue.vlm

import android.graphics.Bitmap
import java.io.File

/**
 * A vision language model on llama.cpp (libmtmd). Use one caller for [describe] and [close].
 * [describe] blocks; run it off the main thread. [cancel] may run on any thread,
 * including during [close]. Calls after [close] fail, except [cancel] and [close].
 */
class VisionModel private constructor(private val maxSide: Int) : AutoCloseable {
    private val lifecycle = Any()
    private var handle = 0L

    /** Answers [prompt] about [image] with greedy decoding, at most [maxTokens] tokens. */
    fun describe(image: Bitmap, prompt: String, maxTokens: Int = 32): String {
        require(maxTokens in 1..4096) { "maxTokens must be between 1 and 4096" }
        check(!image.isRecycled) { "Image has been recycled" }
        val current = synchronized(lifecycle) {
            check(handle != 0L) { "Vision model is closed" }
            nativeSetCancelled(handle, false)
            handle
        }
        val input = encoderInput(image)
        try {
            return nativeDescribe(current, input, prompt.toByteArray(Charsets.UTF_8), maxTokens)
                .toString(Charsets.UTF_8)
        } finally {
            if (input !== image) input.recycle()
        }
    }

    /** Scales the image down to [maxSide] on the long side and makes it readable by native code. */
    private fun encoderInput(image: Bitmap): Bitmap {
        val scale = maxSide.toFloat() / maxOf(image.width, image.height)
        if (scale < 1f) {
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(image, w, h, true)
            if (scaled.config == Bitmap.Config.ARGB_8888) return scaled
            return checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false)).also { if (scaled !== image) scaled.recycle() }
        }
        return if (image.config == Bitmap.Config.ARGB_8888) image else
            checkNotNull(image.copy(Bitmap.Config.ARGB_8888, false)) { "Cannot copy image" }
    }

    /** Stops a running [describe] call. It then returns the text generated so far. */
    fun cancel() = synchronized(lifecycle) {
        if (handle != 0L) nativeSetCancelled(handle, true)
    }

    override fun close() = synchronized(lifecycle) {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeLoad(model: ByteArray, mmproj: ByteArray, threads: Int, imageTokens: Int): Long
    private external fun nativeDescribe(handle: Long, image: Bitmap, prompt: ByteArray, maxTokens: Int): ByteArray
    private external fun nativeSetCancelled(handle: Long, cancelled: Boolean)
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("cirvue_vlm") }

        /**
         * @param maxSide longest image side given to the encoder. SmolVLM2 splits images larger
         * than its 512 px tile into several tiles and encodes each one. One tile is enough for
         * a selected object: on redroid, 512 px took 4.4 s and 768 px 11.1 s, with the same answer.
         * @param imageTokens most tokens one image can use, for encoders with a variable image
         * size such as Gemma 4. 0 keeps the default of the model.
         */
        fun load(model: File, mmproj: File, threads: Int = 4, maxSide: Int = 512, imageTokens: Int = 0): VisionModel {
            require(threads > 0) { "threads must be positive" }
            require(maxSide > 0) { "maxSide must be positive" }
            require(imageTokens >= 0) { "imageTokens must not be negative" }
            return VisionModel(maxSide).apply {
                handle = nativeLoad(model.absolutePath.toByteArray(Charsets.UTF_8),
                    mmproj.absolutePath.toByteArray(Charsets.UTF_8), threads, imageTokens)
            }
        }
    }
}
