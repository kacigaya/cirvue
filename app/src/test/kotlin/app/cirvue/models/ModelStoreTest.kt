package app.cirvue.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStoreTest {
    @Test fun toHexUsesTwoLowercaseDigitsForUnsignedByteValues() {
        assertEquals("00010f107f80abff", byteArrayOf(0, 1, 15, 16, 127, -128, -85, -1).toHex())
    }

    @Test fun emptyBytesProduceEmptyHex() {
        assertEquals("", byteArrayOf().toHex())
    }
}
