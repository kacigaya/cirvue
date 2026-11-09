package app.cirvue.capture

import android.graphics.Bitmap

/** Where an image to search comes from. */
enum class Source { Screen, Camera }

/**
 * Hands one image from the assistant session or the camera to `SearchActivity` inside the
 * process. A screenshot is far larger than the Binder limit for Intent extras, and writing it
 * to disk would break the no-storage rule. A null image means the screenshot was blocked.
 */
object CaptureHolder {
    class Capture(val image: Bitmap?, val source: Source)

    @Volatile private var pending: Capture? = null

    fun put(image: Bitmap?, source: Source) {
        pending = Capture(image, source)
    }

    /** Returns the pending capture once, then forgets it. */
    fun take(): Capture? = pending.also { pending = null }
}
