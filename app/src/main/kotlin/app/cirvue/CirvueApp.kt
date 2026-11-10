package app.cirvue

import android.app.Application
import android.content.ComponentCallbacks2
import app.cirvue.models.ModelStore
import app.cirvue.recognize.Recognizer
import app.cirvue.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Holds the few app-wide objects. No DI framework: three singletons do not need one. */
class CirvueApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings by lazy { Settings(this) }
    val models by lazy { ModelStore(this) }
    val recognizer by lazy { Recognizer(models) }

    @Suppress("DEPRECATION") // TRIM_MEMORY_BACKGROUND is the documented signal we need.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The vision model holds from several hundred MB to 2 GB. Free it once the app leaves the screen.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            scope.launch { recognizer.release() }
        }
    }
}
