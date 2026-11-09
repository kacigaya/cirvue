package app.cirvue

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.cirvue.capture.Source
import app.cirvue.recognize.Recognizer
import app.cirvue.search.Query
import app.cirvue.select.Selection
import app.cirvue.select.SelectionGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tool { Draw, Box }

sealed interface Stage {
    data object Camera : Stage
    /** The screenshot was blocked or turned off. */
    data object Blocked : Stage
    data class Select(val image: Bitmap, val source: Source) : Stage
}

sealed interface Panel {
    data object Hidden : Panel
    data object Working : Panel
    data class Ready(val query: Query) : Panel
    data object NoModels : Panel
    data object NothingFound : Panel
    data object Failed : Panel
}

/**
 * State of one search: the image, the selection, and the query. It lives in the activity, which
 * handles configuration changes itself, so the image never needs to be saved.
 */
class SearchState(
    private val scope: CoroutineScope,
    private val recognizer: Recognizer,
    private val autoSearch: () -> Boolean,
    private val search: (String) -> Unit,
) {
    var stage: Stage by mutableStateOf(Stage.Camera)
        private set
    var tool by mutableStateOf(Tool.Draw)
    var selection: Selection? by mutableStateOf(null)
        private set
    var panel: Panel by mutableStateOf(Panel.Hidden)
        private set
    var query by mutableStateOf("")
    var textBoxes: List<Rect> by mutableStateOf(emptyList())
        private set

    private var job: Job? = null
    private var boxesJob: Job? = null

    fun show(stage: Stage) {
        job?.cancel(); boxesJob?.cancel()
        this.stage = stage
        selection = null
        panel = Panel.Hidden
        query = ""
        textBoxes = emptyList()
        if (stage is Stage.Select) {
            boxesJob = scope.launch {
                runCatching { recognizer.textBoxes(stage.image) }.onSuccess { boxes ->
                    textBoxes = boxes.map { Rect(it.left, it.top, it.right, it.bottom) }
                }
                runCatching { recognizer.warmUp() }
            }
        }
    }

    fun select(selection: Selection) {
        val stage = stage as? Stage.Select ?: return
        this.selection = selection
        job?.cancel()
        if (!recognizer.hasText && !recognizer.hasVision) {
            panel = Panel.NoModels
            return
        }
        panel = Panel.Working
        job = scope.launch {
            panel = try {
                val crop = withContext(Dispatchers.Default) { crop(stage.image, selection.bounds) }
                val result = recognizer.recognize(crop)
                query = result.text
                if (result.text.isBlank()) {
                    Panel.NothingFound
                } else {
                    if (autoSearch()) search(result.text)
                    Panel.Ready(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Panel.Failed
            }
        }
    }

    fun submit() {
        val text = query.trim()
        if (text.isNotEmpty()) search(text)
    }

    fun pickSuggestion(text: String) {
        query = text
        search(text)
    }

    private fun crop(image: Bitmap, bounds: Rect): Bitmap {
        val r = SelectionGeometry.cropRect(bounds, Size(image.width.toFloat(), image.height.toFloat()))
        val x = r.left.toInt()
        val y = r.top.toInt()
        val w = r.width.toInt().coerceIn(1, image.width - x)
        val h = r.height.toInt().coerceIn(1, image.height - y)
        return Bitmap.createBitmap(image, x, y, w, h)
    }
}
