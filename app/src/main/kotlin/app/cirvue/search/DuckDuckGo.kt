package app.cirvue.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import java.net.URLEncoder

/** The DuckDuckGo results tab to open. */
enum class Vertical(internal val tab: String?) {
    Web(null),
    Images("images"),
    Shopping("shopping"),
}

object DuckDuckGo {
    /**
     * The documented search URL. No setting parameters: the user's DuckDuckGo settings and
     * DuckDuckGo's own parameters stay untouched. `ia` and `iax` only select the results tab.
     * DuckDuckGo uses them in its own tab links but does not document them. If they stop
     * working, the web results open.
     */
    fun searchUrl(query: String, vertical: Vertical = Vertical.Web): String =
        "https://duckduckgo.com/?q=" + URLEncoder.encode(query.trim(), "UTF-8") +
            vertical.tab?.let { "&ia=$it&iax=$it" }.orEmpty()

    /** Opens the results in a Custom Tab, or in any browser if none supports Custom Tabs. */
    fun open(context: Context, query: String, vertical: Vertical = Vertical.Web): Boolean {
        val uri = searchUrl(query, vertical).toUri()
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
