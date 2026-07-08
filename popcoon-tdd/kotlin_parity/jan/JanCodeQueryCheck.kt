package io.github.shizukutanaka.popcoon.feature.barcode

/**
 * Standalone execution check for JanCodeQuery (JAN/EAN check-digit validation).
 * No Android SDK: JanCodeQuery is a self-contained pure object.
 *
 * Vectors are computed by an INDEPENDENT Python implementation of the standard
 * EAN-13/EAN-8/UPC-A check-digit algorithm (see commit message), so agreement
 * confirms the Kotlin weighting/indexing rather than echoing it.
 */
private var fails = 0

private fun check(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        println("MISMATCH [$name]: expected=$expected actual=$actual")
        fails++
    }
}

fun main() {
    // ── Valid JAN-13 (independent check digits) ────────────────────────────
    val validJan13 = listOf("4901234567894", "4567890123456", "0000000000000", "9784101092058")
    for (c in validJan13) check("valid jan13 $c", true, JanCodeQuery.isValidJan13(c))
    // Wrong check digit -> invalid (flip last digit)
    for (c in validJan13) {
        val bad = c.dropLast(1) + ((c.last() - '0' + 1) % 10).toString()
        check("bad-check jan13 $bad", false, JanCodeQuery.isValidJan13(bad))
    }
    // Length / format edges
    check("jan13 wrong length", false, JanCodeQuery.isValidJan13("490123456789"))
    check("jan13 non-digit", false, JanCodeQuery.isValidJan13("49012345678AB".take(13)))

    // ── Valid JAN-8 ────────────────────────────────────────────────────────
    val validJan8 = listOf("49123456", "00000000", "49000009")
    for (c in validJan8) check("valid jan8 $c", true, JanCodeQuery.isValidJan8(c))
    for (c in validJan8) {
        val bad = c.dropLast(1) + ((c.last() - '0' + 1) % 10).toString()
        check("bad-check jan8 $bad", false, JanCodeQuery.isValidJan8(bad))
    }

    // ── toSearchQuery ──────────────────────────────────────────────────────
    check("query valid jan13 passthrough", "4901234567894", JanCodeQuery.toSearchQuery(" 4901234567894 "))
    check("query valid jan8 passthrough", "49123456", JanCodeQuery.toSearchQuery("49123456"))
    // UPC-12 036000291452 -> "0"+upc = valid JAN-13
    check("query upc12 -> jan13", "0036000291452", JanCodeQuery.toSearchQuery("036000291452"))
    check("query invalid -> null", null, JanCodeQuery.toSearchQuery("1234567890123"))

    // ── countryFromJan13 ───────────────────────────────────────────────────
    check("country JP (49)", "JP", JanCodeQuery.countryFromJan13("4901234567894"))
    check("country US/CA (0)", "US/CA", JanCodeQuery.countryFromJan13("0036000291452"))

    if (fails == 0) {
        println("JAN CODE QUERY: all assertions passed")
    } else {
        println("JAN CODE QUERY: $fails assertion(s) FAILED")
        kotlin.system.exitProcess(1)
    }
}
