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

    @Test fun blankQueryHasEmptyParameter() {
        assertEquals("https://duckduckgo.com/?q=", DuckDuckGo.searchUrl(" \t\n"))
    }
}
