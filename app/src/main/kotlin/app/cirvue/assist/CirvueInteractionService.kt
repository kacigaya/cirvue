package app.cirvue.assist

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** Makes Cirvue selectable as the device's digital assistant. */
class CirvueInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Cirvue needs the screenshot only. Without this, the system also collects the view
        // structure of the current app (its text and layout) and hands it to the session.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST)
    }
}
