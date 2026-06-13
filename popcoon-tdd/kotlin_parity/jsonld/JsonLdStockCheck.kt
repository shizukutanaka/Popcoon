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

    println("JSON-LD STOCK: all assertions passed")
}
