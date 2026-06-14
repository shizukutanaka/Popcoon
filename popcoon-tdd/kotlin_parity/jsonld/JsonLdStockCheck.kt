package com.example.popcoon.data.network

/**
 * stockFromAvailability (FallbackScraper の在庫抽出) の実行検証ハーネス (Android SDK 不要)。
 * run_jsonld.sh から JsonLdStock.kt と一緒にコンパイル・実行する。
 *
 * extractJsonString は ktor 依存の FallbackScraper 内にあるためここでは正規表現を複製して
 * 「flat キー検索が nested offers.availability に届く」ことを demonstrate する
 * (検証の主対象は本物の stockFromAvailability)。
 */
private fun extractJsonStringMirror(json: String, key: String): String? =
    Regex("""["']$key["']\s*:\s*["']((?:[^"\\]|\\.)*)["']""").find(json)?.groupValues?.get(1)

fun main() {
    val cases = listOf(
        "https://schema.org/OutOfStock" to 0, "OutOfStock" to 0,
        "https://schema.org/SoldOut" to 0, "Discontinued" to 0,
        "https://schema.org/InStock" to null, "InStock" to null,
        "https://schema.org/LimitedAvailability" to null, "" to null,
    )
    for ((raw, exp) in cases) {
        check(stockFromAvailability(raw) == exp) { "stockFromAvailability($raw) != $exp" }
    }
    check(stockFromAvailability(null) == null) { "null -> null" }

    // End-to-end: nested JSON-LD offers.availability -> extracted -> mapped to 0.
    val ld = """{"@type":"Product","name":"X","offers":{"@type":"Offer","price":"1980","availability":"https://schema.org/OutOfStock"}}"""
    val extracted = extractJsonStringMirror(ld, "availability")
    check(extracted == "https://schema.org/OutOfStock") { "extract failed: $extracted" }
    check(stockFromAvailability(extracted) == 0) { "nested OutOfStock -> 0" }
    check(stockFromAvailability(extractJsonStringMirror("""{"offers":{"availability":"https://schema.org/InStock"}}""", "availability")) == null) { "InStock -> null" }

    // ── normalizeOriginCountry: 表記ゆれ → EcoEthicsScorer の ISO-2 キー ──────
    val countryCases = listOf(
        "JP" to "JP", "jp" to "JP", "Japan" to "JP", "日本" to "JP", "JPN" to "JP",
        "Germany" to "DE", "ドイツ" to "DE", "DEU" to "DE",
        "USA" to "US", "United States" to "US", "米国" to "US", "America" to "US",
        "China" to "CN", "中国" to "CN", "CHN" to "CN",
        "Vietnam" to "VN", "ベトナム" to "VN",
        "South Korea" to "KR", "韓国" to "KR", "KOR" to "KR",
        "India" to "IN", "インド" to "IN",
        "Bangladesh" to "BD", "バングラデシュ" to "BD",
        // schema.org が URL/コード混在で出す場合も末尾要素で解決
        "https://schema.org/JP" to "JP",
        // 未対応・空 → null (既定値 0.60 に落ちる前に検出可能)
        "Mars" to null, "" to null, "  " to null, "UK" to null, "France" to null,
    )
    for ((raw, exp) in countryCases) {
        val got = normalizeOriginCountry(raw)
        check(got == exp) { "normalizeOriginCountry($raw) = $got, expected $exp" }
    }
    check(normalizeOriginCountry(null) == null) { "null -> null" }

    // End-to-end: JSON-LD countryOfOrigin -> extracted -> normalized to eco key.
    val ld2 = """{"@type":"Product","name":"X","countryOfOrigin":"Japan","price":"1980"}"""
    check(normalizeOriginCountry(extractJsonStringMirror(ld2, "countryOfOrigin")) == "JP") {
        "JSON-LD countryOfOrigin Japan -> JP"
    }

    // ── normalizeGtin: schema.org gtin -> ProductMatcher 用 janCode ──────────
    val gtinCases = listOf(
        "4901234567894" to "4901234567894",   // JAN-13
        "49123456" to "49123456",              // JAN-8
        "036000291452" to "036000291452",      // UPC-12
        "00012345678905" to "00012345678905",  // GTIN-14
        "4901234-567894".let { it } to "4901234567894",  // ハイフン除去
        " 4901234567894 " to "4901234567894",  // 前後空白
        "12345" to null,                        // 桁数不正
        "ABCDEFGHIJKLM" to null,                // 非数字
        "" to null,
    )
    for ((raw, exp) in gtinCases) {
        check(normalizeGtin(raw) == exp) { "normalizeGtin($raw) = ${normalizeGtin(raw)}, expected $exp" }
    }
    check(normalizeGtin(null) == null) { "gtin null -> null" }
    // End-to-end: JSON-LD gtin13 -> extracted -> normalized janCode.
    val ld3 = """{"@type":"Product","name":"X","gtin13":"4901234567894"}"""
    check(normalizeGtin(extractJsonStringMirror(ld3, "gtin13")) == "4901234567894") {
        "JSON-LD gtin13 -> janCode"
    }

    println("JSON-LD STOCK: all assertions passed")
    println("ORIGIN COUNTRY: all assertions passed")
    println("GTIN/JAN: all assertions passed")
}
