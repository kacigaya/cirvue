package app.cirvue.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GtinTest {
    @Test fun checkDigitDecidesForEanAndUpc() {
        assertTrue(Gtin.isValid("4006381333931"))
        assertTrue(Gtin.isValid("036000291452"))
        assertFalse(Gtin.isValid("4006381333932"))
        assertFalse(Gtin.isValid("036000291453"))
    }

    @Test fun otherLengthsAreNotCodes() {
        // 73513537 is a valid EAN-8. It is left out on purpose.
        for (code in listOf("", "73513537", "40063813339310", "400638133393")) assertFalse(code, Gtin.isValid(code))
    }

    @Test fun findReadsOneLineWithSpacesAndBarcodeMarks() {
        assertEquals("4006381333931", Gtin.find(listOf("Gummy bears", "4 006381 333931 >")))
        assertEquals("036000291452", Gtin.find(listOf("0 36000 29145 2")))
        // PP-OCR read the guard bars of a real barcode as quotes.
        assertEquals("4620017455554", Gtin.find(listOf("4\"620017\"455554")))
    }

    @Test fun findJoinsDigitLinesThatFollowEachOther() {
        assertEquals("4006381333931", Gtin.find(listOf("4", "006381", "333931")))
        assertNull(Gtin.find(listOf("4", "006381", "text", "333931")))
    }

    @Test fun findIgnoresDigitsInsideOtherText() {
        assertNull(Gtin.find(listOf("Tel 4006381333931", "+4006381333931", "4006381-333931")))
    }
}
