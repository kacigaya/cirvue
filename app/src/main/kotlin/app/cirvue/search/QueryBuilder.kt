package app.cirvue.search

/**
 * What the vision model named in the selection, for display.
 * @param type the general kind of object, such as "Stand mixer".
 * @param name the brand, model, place, or species. Empty if the model gave none.
 * @param confirmed true when text read from the image contains the name.
 */
data class Seen(val type: String, val name: String, val confirmed: Boolean)

/**
 * A search query and up to [QueryBuilder.MAX_SUGGESTIONS] alternatives the user can pick instead.
 * @param seen what the vision model named, or null when the query comes from text alone.
 * @param productCode true when the query is a product code read from the image.
 * @param review true when the user must see the query before it leaves the device.
 */
data class Query(
    val text: String,
    val suggestions: List<String> = emptyList(),
    val seen: Seen? = null,
    val productCode: Boolean = false,
    val review: Boolean = false,
)

/**
 * Turns local recognition output into a DuckDuckGo query. Pure and deterministic, so the rules
 * that stop invented product names and private text are easy to test.
 */
object QueryBuilder {
    const val MAX_SUGGESTIONS = 3
    private const val MAX_OBJECT_WORDS = 8
    private const val MAX_TEXT_CHARS = 200
    private const val MAX_KEY_TERMS = 2
    private const val MAX_NOISE_CHARS = 3

    /** A longer text query is a sentence or a message, not a name. The user sees it first. */
    private const val MAX_AUTO_TEXT_WORDS = 12

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
    private val field = Regex("""^(type|brand|name|query)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val unknown = setOf("", "unknown", "none", "n/a", "not sure", "not visible")
    private val email = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")

    /** Seven or more digits, with the separators used in phone, card, and account numbers. */
    private val longNumber = Regex("""\d(?:[\s.\-()/]*\d){6,}""")
    private val quotes = "\"'`“”‘’«»"
    private val whitespace = Regex("""\s+""")

    /**
     * @param ocrLines text lines read from the crop, in reading order.
     * @param textCoverage share of the crop area covered by text boxes, 0 to 1.
     * @param vlmOutput raw text from the vision model, or null if it is not installed.
     */
    fun build(ocrLines: List<String>, textCoverage: Float, vlmOutput: String?): Query {
        val all = ocrLines.map(::collapse).filter { it.isNotEmpty() }
        // Icons often read as 1 to 3 stray characters. Keep them only if nothing longer exists.
        val lines = all.filter { it.length > MAX_NOISE_CHARS }.ifEmpty { all }
        val text = joinText(lines)
        val read = normalizedTokens(lines.joinToString(" "))
        val answer = vlmOutput?.let(::parse)
        val words = answer?.let { words(it.query) }.orEmpty()
        val described = words.filter { !looksLikeIdentifier(it) || normalize(it) in read }
            .take(MAX_OBJECT_WORDS)
            .joinToString(" ")
        val code = Gtin.find(all)

        // A selection that is mostly the code is a barcode. The code finds the exact product.
        val barcode = code?.takeIf { it.length * 2 >= all.sumOf { line -> line.count(Char::isLetterOrDigit) } }
        val codeOnly = barcode != null
        val fromText = text.isNotEmpty() && (textCoverage >= TEXT_SELECTION_COVERAGE || described.isEmpty())
        val query = barcode ?: if (fromText) text else withKeyTerms(described, lines)
        // The model answer before the identifier check. The user can still pick it.
        val guess = words.take(MAX_OBJECT_WORDS).joinToString(" ").takeIf { it != described }
        val suggestions = (listOfNotNull(code, guess) + lines.filter { it.length in 3..60 })
            .filter { it.isNotEmpty() && !it.equals(query, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(MAX_SUGGESTIONS)
        return Query(
            text = query,
            suggestions = suggestions,
            seen = answer?.seen(read).takeIf { !codeOnly && !fromText },
            productCode = codeOnly,
            review = looksPrivate(query) || (fromText && !codeOnly && query.count { it == ' ' } >= MAX_AUTO_TEXT_WORDS),
        )
    }

    /** True if [query] holds an email address or a long number, such as a phone or card number. */
    internal fun looksPrivate(query: String): Boolean =
        email.containsMatchIn(query) || longNumber.containsMatchIn(query)

    /** The vision model answer. Free text from a model that uses no fields is all [query]. */
    private class Answer(val type: String, val name: String, val query: String) {
        fun seen(read: Set<String>): Seen? {
            if (type.isEmpty() && name.isEmpty()) return null
            // Words that only repeat the type, like "can" in "Coca-Cola can", need no proof.
            val proper = normalizedTokens(name) - normalizedTokens(type)
            return Seen(type.replaceFirstChar(Char::titlecase), name, proper.isNotEmpty() && read.containsAll(proper))
        }
    }

    /** Reads `type:`, `brand:`, `name:`, and `query:` lines. "unknown" counts as empty. */
    private fun parse(raw: String): Answer {
        val fields = HashMap<String, String>()
        for (line in raw.lineSequence()) {
            val match = field.matchEntire(collapse(line)) ?: continue
            val value = match.groupValues[2].trim(*quotes.toCharArray(), ' ', '.')
            fields.putIfAbsent(match.groupValues[1].lowercase(), value.takeIf { it.lowercase() !in unknown }.orEmpty())
        }
        if (fields.isEmpty()) return Answer("", "", raw.lineSequence().map(::collapse).firstOrNull { it.isNotEmpty() }.orEmpty())
        val type = fields["type"].orEmpty()
        val brand = fields["brand"].orEmpty()
        val model = fields["name"].orEmpty()
        val name = if (brand.isEmpty() || model.contains(brand, ignoreCase = true)) model.ifEmpty { brand } else "$brand $model".trim()
        return Answer(type, name, fields["query"].orEmpty().ifEmpty { "$name $type".trim() })
    }

    /** Removes prompts, quotes, and filler from one line of the answer and splits it into words. */
    private fun words(raw: String): List<String> {
        var line = raw.replace(labelPrefix, "").trim(*quotes.toCharArray(), ' ')
        line = line.replace(fillerPrefix, "").trimEnd('.', '!', '?', ',', ';', ':').trim()
        return line.split(' ').filter { it.isNotEmpty() }
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
