package app.cirvue.search

/**
 * Finds a product code (GTIN-12 or GTIN-13, the digits printed under UPC and EAN barcodes) in
 * OCR text. Codes of 8 digits are left out: dates and one-time codes have that length too.
 * Cirvue reads the printed digits, not the bars, so it needs no barcode library.
 */
object Gtin {
    /**
     * The first code in [lines], without spaces. A code is one line of digits, or digit lines
     * that follow each other, because OCR can split the groups of a barcode into several boxes.
     * OCR reads the long guard bars between the groups as quotes or bars, so those can stand
     * between digits. Signs used in phone numbers and prices cannot.
     */
    fun find(lines: List<String>): String? {
        var run = ""
        for (line in lines + "") {
            val digits = line.filterNot { it in BAR_MARKS }
            if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
                if (isValid(digits)) return digits
                run += digits
            } else {
                if (isValid(run)) return run
                run = ""
            }
        }
        return null
    }

    private const val BAR_MARKS = " <>|\"'"

    /** The last digit is a check digit: weights 3 and 1 alternate from the digit before it. */
    internal fun isValid(code: String): Boolean {
        if (code.length != 12 && code.length != 13) return false
        val sum = code.dropLast(1).reversed().withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 3 else 1 }
        return (10 - sum % 10) % 10 == code.last() - '0'
    }
}
