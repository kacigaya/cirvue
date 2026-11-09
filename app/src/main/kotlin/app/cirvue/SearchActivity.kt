package app.cirvue

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.cirvue.capture.CaptureHolder
import app.cirvue.capture.Source
import app.cirvue.search.DuckDuckGo
import app.cirvue.ui.SearchScreen
import app.cirvue.ui.theme.CirvueTheme

/** Selection and search over a screenshot ([ACTION_SCREEN]) or a camera photo ([ACTION_CAMERA]). */
class SearchActivity : ComponentActivity() {
    private val app get() = application as CirvueApp
    private lateinit var state: SearchState

    /**
     * True while another activity we started is on top (browser, permission dialog, photo
     * picker), so leaving for it does not close the search.
     */
    private var external = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The image is drawn edge to edge, so the bars are always over a dark scrim.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        state = SearchState(
            scope = lifecycleScope,
            recognizer = app.recognizer,
            autoSearch = { !app.settings.reviewQuery.value },
            search = ::openResults,
        )
        // After process death the in-memory image is gone; there is nothing to restore.
        if (savedInstanceState != null || !route(intent)) {
            finish()
            return
        }
        setContent {
            CirvueTheme(darkTheme = true) {
                SearchScreen(
                    state = state,
                    onCaptured = { state.show(Stage.Select(it, Source.Camera)) },
                    onCamera = { state.show(Stage.Camera) },
                    onExternal = { external = true },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!route(intent)) finish()
    }

    override fun onResume() {
        super.onResume()
        external = false
    }

    /** Home or Recents ends the search, like closing the overlay. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!external) finish()
    }

    private fun route(intent: Intent): Boolean {
        when (intent.action) {
            ACTION_CAMERA -> state.show(Stage.Camera)
            ACTION_SCREEN -> {
                val capture = CaptureHolder.take() ?: return false
                val image = capture.image?.let(::softwareCopy)
                state.show(if (image == null) Stage.Blocked else Stage.Select(image, capture.source))
            }
            else -> return false
        }
        return true
    }

    private fun openResults(query: String) {
        external = true
        if (!DuckDuckGo.open(this, query)) {
            external = false
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_SCREEN = "app.cirvue.action.SCREEN"
        const val ACTION_CAMERA = "app.cirvue.action.CAMERA"

        /** Screenshots can arrive as hardware bitmaps, which cannot be cropped or read. */
        private fun softwareCopy(bitmap: Bitmap): Bitmap? =
            if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    }
}
