package app.cirvue.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrMathTest {
    @Test fun dictionaryPreservesQuotedCharactersAndUnicode() {
        val dictionary = parseDictionary("""
            PostProcess:
              character_dict:
              - '!'
              - ''''
              - "\\u0041"
              - 🛅
              - 　
            Other: true
        """.trimIndent().replace("\\\\u", "\\u"))
        assertEquals(listOf("", "!", "'", "A", "🛅", "　", " "), dictionary)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingDictionaryFails() { parseDictionary("name: rec") }

    @Test fun ctcCollapsesRepeatsButBlankSeparatesThem() {
        val dictionary = listOf("", "A", "B", " ")
        val winners = listOf(0, 1, 1, 0, 1, 2, 3, 3)
        val data = FloatArray(winners.size * dictionary.size) { .01f }
        winners.forEachIndexed { t, c -> data[t * dictionary.size + c] = .9f }
        val decoded = decodeCtc(data, winners.size, dictionary)
        assertEquals("AAB ", decoded.first)
        assertEquals(.9f, decoded.second, .00001f)
        assertEquals("" to 0f, decodeCtc(floatArrayOf(1f, 0f, 0f, 0f), 1, dictionary))
    }

    @Test(expected = IllegalArgumentException::class)
    fun ctcRejectsWrongClassCount() { decodeCtc(FloatArray(3), 1, listOf("", "a")) }

    @Test fun resizeMatchesHalfEvenAndLimits() {
        assertEquals(Size(1088, 2400), detectionSize(1080, 2400))
        assertEquals(Size(64, 128), detectionSize(20, 40))
        assertEquals(Size(448, 960), detectionSize(1080, 2400, 960, "max"))
        assertEquals(Size(32, 4000), detectionSize(1, 10000))
        assertEquals(Size(64, 64), detectionSize(80, 80)) // 2.5 rounds to even.
        assertEquals(Size(3200, 48), recognitionSize(10000, 1))
        assertEquals(Size(144, 48), recognitionSize(90, 30))
    }

    @Test fun tensorHasCorrectChannelsNormalizationAndPadding() {
        val red = intArrayOf(0xffff0000.toInt())
        val det = imageTensor(red, 1, 1, Size(1, 1), true)
        assertEquals(-.485f / .229f, det[0], .00001f)
        assertEquals((1f - .406f) / .225f, det[2], .00001f)
        assertArrayEquals(floatArrayOf(1f, 0f, -1f, 0f, -1f, 0f),
            imageTensor(red, 1, 1, Size(1, 1), false, 2), 0f)
    }

    @Test fun unclipUsesAreaOverPerimeter() {
        assertEquals(Box(-2f, -2f, 12f, 12f), unclip(Box(0f, 0f, 10f, 10f), .8f))
    }

    @Test fun dbScoresWholeBoxRejectsSmallAndClamps() {
        val map = FloatArray(20 * 20)
        for (y in 0..8) for (x in 0..8) map[y * 20 + x] = .9f
        val boxes = dbBoxes(map, 20, 20, 40, 40)
        assertEquals(1, boxes.size)
        assertEquals(0f, boxes.single().left, 0f)
        assertEquals(22f, boxes.single().right, 0f)
        assertTrue(dbBoxes(map, 20, 20, 40, 40, boxThreshold = .95f).isEmpty())
        assertTrue(dbBoxes(FloatArray(400), 20, 20, 40, 40).isEmpty())
        val tiny = FloatArray(400); tiny[0] = 1f
        assertTrue(dbBoxes(tiny, 20, 20, 40, 40).isEmpty())
    }

    @Test fun fastScoreIncludesBackgroundInsideComponentBox() {
        val map = FloatArray(400)
        for (y in 1..10) for (x in 1..10) {
            if (y == 1 || y == 10 || x == 1 || x == 10) map[y * 20 + x] = 1f
        }
        assertTrue(dbBoxes(map, 20, 20, 20, 20).isEmpty()) // Mean is .36, below .45.
        assertEquals(1, dbBoxes(map, 20, 20, 20, 20, boxThreshold = .35f).size)
    }

    @Test fun dbUsesEightConnectedPixelsAndCandidateLimit() {
        val map = FloatArray(400)
        for (y in 1..4) for (x in 1..4) map[y * 20 + x] = 1f
        for (y in 5..8) for (x in 5..8) map[y * 20 + x] = 1f
        for (y in 12..16) for (x in 12..16) map[y * 20 + x] = 1f
        assertEquals(2, dbBoxes(map, 20, 20, 20, 20).size)
        assertEquals(1, dbBoxes(map, 20, 20, 20, 20, maxCandidates = 1).size)
    }

    @Test fun sortingUsesLineToleranceThenLeftEdge() {
        val right = Box(50f, 10f, 90f, 30f)
        val left = Box(0f, 15f, 40f, 35f)
        val next = Box(0f, 60f, 40f, 80f)
        assertEquals(listOf(left, right, next), sortBoxes(listOf(next, right, left)))
    }
}
