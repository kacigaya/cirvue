package app.cirvue.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryBuilderTest {
    @Test fun shortNoiseLinesDropWhenLongerTextExists() {
        assertEquals("Connected devices Bluetooth", QueryBuilder.build(listOf("Connected devices", "Co0", "Bluetooth"), 1f, null).text)
        assertEquals("OK", QueryBuilder.build(listOf("OK"), 1f, null).text)
    }

    @Test fun textAtCoverageThresholdWinsOverDescription() {
        val lines = listOf("  Read\tthis  ", " next line ")
        for (coverage in listOf(QueryBuilder.TEXT_SELECTION_COVERAGE, 1f)) {
            assertEquals("Read this next line", QueryBuilder.build(lines, coverage, "red chair").text)
        }
        assertEquals("red chair", QueryBuilder.build(lines, QueryBuilder.TEXT_SELECTION_COVERAGE - 0.001f, "red chair").text)
    }

    @Test fun longTextEndsAtLastWordBoundaryWithin200Characters() {
        val prefix = List(39) { "word" }.joinToString(" ") // 194 characters.
        val result = QueryBuilder.build(listOf("$prefix longer trailing"), 1f, null)
        assertEquals(prefix, result.text)
        assertTrue(result.text.length <= 200)
    }

    @Test fun textOfExactly200CharactersIsKept() {
        val text = "a".repeat(195) + " word"
        assertEquals(text, QueryBuilder.build(listOf(text), 1f, null).text)
    }

    @Test fun descriptionRemovesLabelsQuotesFillerAndTrailingPunctuation() {
        for (label in listOf("Search query:", "Web search:", "Query:", "Answer:", "Output:", "Result:")) {
            assertEquals("red chair", QueryBuilder.build(emptyList(), 0f, "$label \"a photo of red chair.!?\"").text)
        }
        assertEquals("red chair", QueryBuilder.build(emptyList(), 0f, "“red chair”").text)
    }

    @Test fun descriptionUsesFirstNonEmptyLineAndAtMostEightWords() {
        val raw = "\n \t\nSearch query: one two three four five six seven eight nine\nother answer"
        assertEquals("one two three four five six seven eight", QueryBuilder.build(emptyList(), 0f, raw).text)
    }

    @Test fun identifiersNotSeenByOcrAreRemoved() {
        assertEquals("wireless headphones", QueryBuilder.build(emptyList(), 0f, "WH-1000XM5 wireless headphones A2337").text)
    }

    @Test fun identifiersSeenByOcrIgnoreCaseAndPunctuation() {
        val result = QueryBuilder.build(listOf("wh1000xm5", "(a-2337)"), 0f, "WH-1000XM5 headphones A2337")
        assertEquals("WH-1000XM5 headphones A2337", result.text)
    }

    @Test fun missingOcrIdentifiersArePrependedOnceWithLimitOfTwo() {
        val result = QueryBuilder.build(listOf("(A2337) a-2337 WH-1000XM5 Z999"), 0f, "silver laptop")
        assertEquals("A2337 WH-1000XM5 silver laptop", result.text)
    }

    @Test fun identifiersAlreadyInDescriptionAreNotPrepended() {
        assertEquals("A2337 silver laptop", QueryBuilder.build(listOf("a-2337"), 0f, "A2337 silver laptop").text)
    }

    @Test fun absentOrEmptyDescriptionFallsBackToOcr() {
        for (description in listOf(null, " \n ", "A2337")) {
            assertEquals("read this", QueryBuilder.build(listOf("read this"), 0f, description).text)
        }
    }

    @Test fun noRecognitionProducesEmptyQuery() {
        assertEquals(Query(""), QueryBuilder.build(listOf(" ", "\t"), 0f, null))
    }

    @Test fun suggestionsAreDistinctIgnoreCaseExcludeQueryAndStopAtThree() {
        val result = QueryBuilder.build(listOf("RED CHAIR", "oak desk", "OAK DESK", "blue lamp", "green sofa", "white table"), 0f, "red chair")
        assertEquals(listOf("oak desk", "blue lamp", "green sofa"), result.suggestions)
    }

    @Test fun suggestionsSkipNoiseAndLinesOver60Characters() {
        val longest = "x".repeat(60)
        val result = QueryBuilder.build(listOf("ab", "abc", "abcd", longest, "x".repeat(61)), 0f, "red chair")
        assertEquals(listOf("abcd", longest), result.suggestions)
    }
}
