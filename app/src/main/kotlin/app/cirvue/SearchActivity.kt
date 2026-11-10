package app.cirvue

import android.content.ClipData
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
import app.cirvue.search.Vertical
import app.cirvue.share.SharedCrop
import app.cirvue.ui.SearchScreen
import app.cirvue.ui.theme.CirvueTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Selection and search over a screenshot ([ACTION_SCREEN]) or a camera photo ([ACTION_CAMERA]). */
class SearchActivity : ComponentActivity() {
    private val app get() = application as CirvueApp
    private lateinit var state: SearchState

    /**
     * True while another activity we started is on top (browser, share sheet, permission
     * dialog, photo picker), so leaving for it does not close the search.
     */
    private var external = false

    /** True from a tap on "Search by image" until the share sheet closes. */
    private var sharing = false

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
            share = ::shareImage,
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
        sharing = false
    }

    /** Home or Recents ends the search, like closing the overlay. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!external) finish()
    }

    private fun route(intent: Intent): Boolean {
        // A new search drops the image that the last one shared.
        SharedCrop.clear()
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

    private fun openResults(query: String, vertical: Vertical) {
        external = true
        if (!DuckDuckGo.open(this, query, vertical)) {
            external = false
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens the share sheet for the selected image. This is the only way an image leaves
     * Cirvue: the user asks for it, and then picks the app that gets the image.
     */
    private fun shareImage(image: Bitmap) {
        // A second tap would replace the image that the first share sheet reads.
        if (sharing) return
        sharing = true
        lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.Default) {
                ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            }
            val uri = SharedCrop.put(this@SearchActivity, jpeg)
            val send = Intent(Intent.ACTION_SEND)
                .setType(SharedCrop.MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The share sheet reads the preview and passes the read grant on from the clip.
            send.clipData = ClipData.newUri(contentResolver, null, uri)
            external = true
            // Always show the sheet, so the user sees which app gets the image.
            val chooser = Intent.createChooser(send, getString(R.string.share_image_title))
                .putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false)
            startActivity(chooser)
        }
    }

    override fun onDestroy() {
        if (isFinishing) SharedCrop.clear()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SCREEN = "app.cirvue.action.SCREEN"
        const val ACTION_CAMERA = "app.cirvue.action.CAMERA"
        private const val JPEG_QUALITY = 90

        /** Screenshots can arrive as hardware bitmaps, which cannot be cropped or read. */
        private fun softwareCopy(bitmap: Bitmap): Bitmap? =
            if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    }
}
