package app.cirvue.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User settings. Small enough for `SharedPreferences`; exposed as `StateFlow` for Compose. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _reviewQuery = MutableStateFlow(prefs.getBoolean(KEY_REVIEW, false))

    /** When true, the user confirms or edits each query before it goes to DuckDuckGo. */
    val reviewQuery: StateFlow<Boolean> = _reviewQuery.asStateFlow()

    fun setReviewQuery(value: Boolean) {
        prefs.edit().putBoolean(KEY_REVIEW, value).apply()
        _reviewQuery.value = value
    }

    private companion object {
        const val KEY_REVIEW = "review_query"
    }
}
