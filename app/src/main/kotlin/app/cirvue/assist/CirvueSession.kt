package app.cirvue.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import app.cirvue.SearchActivity
import app.cirvue.capture.CaptureHolder
import app.cirvue.capture.Source

/**
 * Receives the screenshot and hands it to [SearchActivity]. The session shows no UI of its own:
 * a normal activity can open Custom Tabs above itself and come back to the selection.
 */
class CirvueSession(context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var handled = false
    private val timeout = Runnable { deliver(null) }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        handled = false
        if (showFlags and SHOW_WITH_SCREENSHOT == 0) {
            // The user turned off "Use screenshot" in the assistant settings.
            deliver(null)
        } else {
            handler.postDelayed(timeout, SCREENSHOT_TIMEOUT_MS)
        }
    }

    /** [screenshot] is null for secure windows (FLAG_SECURE) and when capture fails. */
    override fun onHandleScreenshot(screenshot: Bitmap?) = deliver(screenshot)

    override fun onHide() {
        handler.removeCallbacks(timeout)
        super.onHide()
    }

    private fun deliver(screenshot: Bitmap?) {
        if (handled) return
        handled = true
        handler.removeCallbacks(timeout)
        CaptureHolder.put(screenshot, Source.Screen)
        context.startActivity(
            Intent(context, SearchActivity::class.java)
                .setAction(SearchActivity.ACTION_SCREEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        hide()
    }

    private companion object {
        const val SCREENSHOT_TIMEOUT_MS = 2_000L
    }
}
