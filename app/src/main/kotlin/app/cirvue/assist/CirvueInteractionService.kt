package app.cirvue.assist

import android.service.voice.VoiceInteractionService

/**
 * Makes Cirvue selectable as the device's digital assistant.
 *
 * Cirvue needs only the screenshot. Android takes the screenshot only when it also collects the
 * assist data of the current app (`AssistDataRequester`), so `setDisabledShowContext` cannot turn
 * that off. [CirvueSession] does not override `onHandleAssist`, so the data is never read.
 */
class CirvueInteractionService : VoiceInteractionService()
