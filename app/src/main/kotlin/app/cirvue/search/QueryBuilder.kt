package app.cirvue.search

/** A search query and up to [QueryBuilder.MAX_SUGGESTIONS] alternatives the user can pick instead. */
data class Query(val text: String, val suggestions: List<String> = emptyList())

/**
 * Turns local recognition output into a DuckDuckGo query. Pure and deterministic, so the rules
 * that stop invented product names are easy to test.
 */
object QueryBuilder {
    const val MAX_SUGGESTIONS = 3
    private const val MAX_OBJECT_WORDS = 8
    private const val MAX_TEXT_CHARS = 200
    private const val MAX_KEY_TERMS = 2

    /** Text covering at least this share of the crop means the user selected text, not an object. */
    const val TEXT_SELECTION_COVERAGE = 0.3f

    private val labelPrefix = Regex(
        """^(?:(?:web\s+)?search(?:\s+query)?|query|answer|output|result)\s*[:\-]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val fillerPrefix = Regex(
        """^(?:(?:a|an|the)\s+)?(?:image|photo|photograph|picture|screenshot)\s+of\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val quotes = "\"'`“”‘’«»"
    private val whitespace = Regex("""\s+""")

    /**
     * @param ocrLines text lines read from the crop, in reading order.
     * @param textCoverage share of the crop area covered by text boxes, 0 to 1.
     * @param vlmOutput raw text from the vision model, or null if it is not installed.
     */
    fun build(ocrLines: List<String>, textCoverage: Float, vlmOutput: String?): Query {
        val lines = ocrLines.map(::collapse).filter { it.isNotEmpty() }
        val text = joinText(lines)
        val described = vlmOutput?.let { described(it, lines) }.orEmpty()

        val query = when {
            text.isNotEmpty() && (textCoverage >= TEXT_SELECTION_COVERAGE || described.isEmpty()) -> text
            else -> withKeyTerms(described, lines)
        }
        val suggestions = lines
            .filter { it.length in 3..60 && !it.equals(query, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(MAX_SUGGESTIONS)
        return Query(query, suggestions)
    }

    /** Cleans the vision model answer and drops identifiers that OCR did not see. */
    internal fun described(raw: String, ocrLines: List<String>): String {
        var line = raw.lineSequence().map(::collapse).firstOrNull { it.isNotEmpty() }.orEmpty()
        line = line.replace(labelPrefix, "").trim(*quotes.toCharArray(), ' ')
        line = line.replace(fillerPrefix, "").trimEnd('.', '!', '?', ',', ';', ':').trim()
        val seen = normalizedTokens(ocrLines.joinToString(" "))
        return line.split(' ')
            .filter { it.isNotEmpty() && (!looksLikeIdentifier(it) || normalize(it) in seen) }
            .take(MAX_OBJECT_WORDS)
            .joinToString(" ")
    }

    /** Adds model numbers and similar identifiers that OCR read but the description lacks. */
    private fun withKeyTerms(described: String, ocrLines: List<String>): String {
        if (described.isEmpty()) return ""
        val present = normalizedTokens(described)
        val terms = ocrLines.asSequence()
            .flatMap { it.split(' ') }
            .map { it.trim(*quotes.toCharArray(), ',', '.', ':', ';', '(', ')') }
            .filter { looksLikeIdentifier(it) && normalize(it) !in present }
            .distinctBy(::normalize)
            .take(MAX_KEY_TERMS)
            .toList()
        return (terms + described).joinToString(" ")
    }

    private fun joinText(lines: List<String>): String {
        val joined = lines.joinToString(" ")
        if (joined.length <= MAX_TEXT_CHARS) return joined
        val cut = joined.lastIndexOf(' ', MAX_TEXT_CHARS)
        return joined.substring(0, if (cut > 0) cut else MAX_TEXT_CHARS)
    }

    /** A token with both letters and digits, like `WH-1000XM5` or `A2337`. */
    internal fun looksLikeIdentifier(token: String): Boolean {
        val core = normalize(token)
        return core.length >= 3 && core.any(Char::isDigit) && core.any(Char::isLetter)
    }

    private fun normalizedTokens(text: String): Set<String> =
        text.split(whitespace).map(::normalize).filter { it.isNotEmpty() }.toSet()

    private fun normalize(token: String): String = token.filter(Char::isLetterOrDigit).lowercase()

    private fun collapse(s: String): String = s.replace(whitespace, " ").trim()
}
