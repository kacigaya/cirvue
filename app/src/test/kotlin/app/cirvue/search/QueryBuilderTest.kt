package app.cirvue.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun fieldAnswerGivesQueryAndWhatWasSeen() {
        val answer = "type: stand mixer\nbrand: KitchenAid\nname: KitchenAid stand mixer\nquery: KitchenAid stand mixer white"
        val result = QueryBuilder.build(listOf("KitchenAid"), 0.05f, answer)
        assertEquals("KitchenAid stand mixer white", result.text)
        assertEquals(Seen("Stand mixer", "KitchenAid stand mixer", confirmed = true), result.seen)
        assertFalse(result.review)
    }

    @Test fun nameWithoutTextInImageIsNotConfirmed() {
        val answer = "type: landmark\nbrand: unknown\nname: Eiffel Tower\nquery: Eiffel Tower in Paris"
        val result = QueryBuilder.build(emptyList(), 0f, answer)
        assertEquals("Eiffel Tower in Paris", result.text)
        assertEquals(Seen("Landmark", "Eiffel Tower", confirmed = false), result.seen)
    }

    @Test fun brandIsJoinedToNameOnlyWhenTheNameLacksIt() {
        val joined = QueryBuilder.build(listOf("SONY"), 0f, "type: headphones\nbrand: Sony\nname: WH-1000XM5\nquery: Sony headphones")
        assertEquals("Sony WH-1000XM5", joined.seen?.name)
        // OCR read the brand but not the model number.
        assertEquals(false, joined.seen?.confirmed)
        val brandOnly = QueryBuilder.build(emptyList(), 0f, "type: sneaker\nbrand: adidas\nname: Unknown.\nquery: white adidas sneaker")
        assertEquals("adidas", brandOnly.seen?.name)
    }

    @Test fun unknownFieldsAreEmptyAndQueryFallsBackToNameAndType() {
        val result = QueryBuilder.build(emptyList(), 0f, "Type: plant\nBrand: Unknown\nName: Monstera deliciosa\nQuery: unknown")
        assertEquals("Monstera deliciosa plant", result.text)
        val nothing = QueryBuilder.build(emptyList(), 0f, "type: unknown\nbrand: unknown\nname: unknown\nquery: unknown")
        assertEquals(Query(""), nothing)
    }

    @Test fun unprovenIdentifierLeavesTheQueryAndBecomesTheFirstSuggestion() {
        val answer = "type: headphones\nbrand: Sony\nname: WH-1000XM5\nquery: Sony WH-1000XM5 headphones"
        val result = QueryBuilder.build(listOf("SONY"), 0f, answer)
        assertEquals("Sony headphones", result.text)
        assertEquals(listOf("Sony WH-1000XM5 headphones", "SONY"), result.suggestions)
    }

    @Test fun textSelectionShowsNoObject() {
        val result = QueryBuilder.build(listOf("Connected devices"), 1f, null)
        assertNull(result.seen)
        assertFalse(result.productCode)
    }

    @Test fun barcodeDigitsBecomeTheQueryWithoutSpaces() {
        for (lines in listOf(listOf("4 006381 333931"), listOf("4", "006381", "333931"))) {
            val result = QueryBuilder.build(lines, 0.1f, "type: barcode\nbrand: unknown\nname: unknown\nquery: barcode label")
            assertEquals("4006381333931", result.text)
            assertTrue(result.productCode)
            assertNull(result.seen)
        }
    }

    @Test fun codeAmongOtherTextIsOnlyASuggestion() {
        val lines = listOf("Gummy bears fruit flavour", "Best before end of the month", "4006381333931")
        val result = QueryBuilder.build(lines, 0.5f, null)
        assertFalse(result.productCode)
        assertEquals("4006381333931", result.suggestions.first())
    }

    @Test fun privateLookingQueriesNeedReview() {
        for (text in listOf("mail jane.doe@example.org now", "call +33 6 12 34 56 78", "card 4111 1111 1111 1111", "4006381333931")) {
            assertTrue(text, QueryBuilder.build(listOf(text), 1f, null).review)
        }
        for (text in listOf("Sony WH-1000XM5", "iPhone 15 Pro 256 GB", "Paris 2024 Olympics")) {
            assertFalse(text, QueryBuilder.build(listOf(text), 1f, null).review)
        }
    }

    @Test fun longTextNeedsReviewButLongDescriptionDoesNot() {
        val twelve = List(12) { "word" }.joinToString(" ")
        assertFalse(QueryBuilder.build(listOf(twelve), 1f, null).review)
        assertTrue(QueryBuilder.build(listOf("$twelve more"), 1f, null).review)
        assertFalse(QueryBuilder.build(emptyList(), 0f, "$twelve more").review)
    }

    @Test fun suggestionsSkipNoiseAndLinesOver60Characters() {
        val longest = "x".repeat(60)
        val result = QueryBuilder.build(listOf("ab", "abc", "abcd", longest, "x".repeat(61)), 0f, "red chair")
        assertEquals(listOf("abcd", longest), result.suggestions)
    }
}
