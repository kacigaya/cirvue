package app.cirvue.search

import org.junit.Assert.assertEquals
import org.junit.Test

class DuckDuckGoTest {
    @Test fun searchUrlTrimsAndEncodesQueryAsUtf8() {
        assertEquals(
            "https://duckduckgo.com/?q=caf%C3%A9+%26+%23%E7%8C%AB",
            DuckDuckGo.searchUrl(" \tcafé & #猫\n "),
        )
    }

    @Test fun imagesAndShoppingAddOnlyTheTabParameters() {
        assertEquals("https://duckduckgo.com/?q=red+chair&ia=images&iax=images", DuckDuckGo.searchUrl("red chair", Vertical.Images))
        assertEquals("https://duckduckgo.com/?q=red+chair&ia=shopping&iax=shopping", DuckDuckGo.searchUrl("red chair", Vertical.Shopping))
        assertEquals(DuckDuckGo.searchUrl("red chair"), DuckDuckGo.searchUrl("red chair", Vertical.Web))
    }

    @Test fun blankQueryHasEmptyParameter() {
        assertEquals("https://duckduckgo.com/?q=", DuckDuckGo.searchUrl(" \t\n"))
    }
}
