package app.cirvue.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URLEncoder

object DuckDuckGo {
    /**
     * The documented search URL. No other parameters: the user's DuckDuckGo settings and
     * DuckDuckGo's own parameters stay untouched.
     */
    fun searchUrl(query: String): String =
        "https://duckduckgo.com/?q=" + URLEncoder.encode(query.trim(), "UTF-8")

    /** Opens the results in a Custom Tab, or in any browser if none supports Custom Tabs. */
    fun open(context: Context, query: String): Boolean {
        val uri = Uri.parse(searchUrl(query))
        return try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(context, uri)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }
}
