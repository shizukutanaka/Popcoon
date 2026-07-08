package io.github.shizukutanaka.popcoon.data.network

/**
 * JsonLdStock.kt の純関数群の実行検証ハーネス (Android SDK 不要)。
 * run_jsonld.sh から JsonLdStock.kt と一緒にコンパイル・実行する。
 *
 * 抽出は**実関数** extractJsonLdString を直接呼ぶ (以前は正規表現を複製した mirror を使っていたが、
 * 複製がドリフトすると production が壊れてもハーネスが緑のまま = 検証の演劇だった。
 * 本関数を JsonLdStock.kt へ抽出し、FallbackScraper.extractJsonString はそこへ委譲する)。
 */
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
    val extracted = extractJsonLdString(ld, "availability")
    check(extracted == "https://schema.org/OutOfStock") { "extract failed: $extracted" }
    check(stockFromAvailability(extracted) == 0) { "nested OutOfStock -> 0" }
    check(stockFromAvailability(extractJsonLdString("""{"offers":{"availability":"https://schema.org/InStock"}}""", "availability")) == null) { "InStock -> null" }

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
    check(normalizeOriginCountry(extractJsonLdString(ld2, "countryOfOrigin")) == "JP") {
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
    check(normalizeGtin(extractJsonLdString(ld3, "gtin13")) == "4901234567894") {
        "JSON-LD gtin13 -> janCode"
    }

    // ── stockFromAmazonAvailability: PA-API Availability -> stockCount ────────
    // Type が明確な在庫切れ → 0
    check(stockFromAmazonAvailability("OutOfStock", null) == 0) { "Type OutOfStock -> 0" }
    check(stockFromAmazonAvailability("SoldOut", null) == 0) { "Type SoldOut -> 0" }
    check(stockFromAmazonAvailability("outofstock", null) == 0) { "Type lower-case -> 0" }
    // 在庫あり系 Type / 不明 → null
    check(stockFromAmazonAvailability("Now", null) == null) { "Type Now -> null" }
    check(stockFromAmazonAvailability(null, null) == null) { "no availability -> null" }
    check(stockFromAmazonAvailability("", "") == null) { "empty -> null" }
    // Message ベース (amazon.co.jp は日本語): 在庫切れ語を含むなら 0
    check(stockFromAmazonAvailability(null, "在庫切れ") == 0) { "Message 在庫切れ -> 0" }
    check(stockFromAmazonAvailability(null, "現在在庫切れです。") == 0) { "Message 在庫切れ文中 -> 0" }
    check(stockFromAmazonAvailability(null, "入荷未定") == 0) { "Message 入荷未定 -> 0" }
    check(stockFromAmazonAvailability(null, "Currently unavailable") == 0) { "Message unavailable -> 0" }
    check(stockFromAmazonAvailability(null, "Out of Stock") == 0) { "Message Out of Stock -> 0" }
    // バックオーダー/発送日数表記は在庫あり扱い (null) — 在庫切れ扱いしない
    check(stockFromAmazonAvailability(null, "在庫あり。") == null) { "Message 在庫あり -> null" }
    check(stockFromAmazonAvailability(null, "通常2〜3日以内に発送します") == null) { "Message ships-in-N-days -> null" }
    check(stockFromAmazonAvailability(null, "残り3点 ご注文はお早めに") == null) { "Message low-stock -> null (still in stock)" }

    // ── extractJsonLdNumber: 引用符なし数値 price (schema.org/Google は "price": 1980 を使う) ──
    // 文字列抽出は引用符付きしか拾えず、数値表記の商品は realPrice=0 になっていた (静かな値喪失)。
    check(extractJsonLdNumber("""{"offers":{"price":1980}}""", "price") == "1980") {
        "unquoted integer price -> 1980"
    }
    check(extractJsonLdNumber("""{"price":38.99,"priceCurrency":"USD"}""", "price") == "38.99") {
        "unquoted decimal price -> 38.99"
    }
    // 引用符付きは数値フォールバックにマッチしない (文字列抽出との役割分担)
    check(extractJsonLdNumber("""{"price":"1980"}""", "price") == null) {
        "quoted price -> number-fallback null"
    }
    check(extractJsonLdNumber("""{"price":1980}""", "lowPrice") == null) { "missing key -> null" }
    // 文字列抽出は引用符なし数値を拾わない (逆方向の役割分担)
    check(extractJsonLdString("""{"price":1980}""", "price") == null) {
        "unquoted number -> string-extract null"
    }

    // ── extractJsonLdArrayFirst: image 文字列配列の先頭要素 (Amazon/楽天は配列形式) ──
    check(extractJsonLdArrayFirst("""{"image":["https://a.jpg","https://b.jpg"]}""", "image")
        == "https://a.jpg") { "image array -> first element" }
    check(extractJsonLdArrayFirst("""{"image": [ "https://x.jpg" ]}""", "image")
        == "https://x.jpg") { "image array with spaces -> first element" }
    // 単一文字列は配列フォールバックにマッチしない (string 抽出の領分)
    check(extractJsonLdArrayFirst("""{"image":"https://single.jpg"}""", "image") == null) {
        "single string image -> array-fallback null"
    }
    check(extractJsonLdString("""{"image":["https://a.jpg"]}""", "image") == null) {
        "image array -> string-extract null"
    }

    // ── extractJsonLdObjectField: brand ネストオブジェクトの name ──
    val brandLd = """{"name":"商品X","brand":{"@type":"Brand","name":"Sony"}}"""
    check(extractJsonLdObjectField(brandLd, "brand", "name") == "Sony") {
        "brand object -> inner name Sony (not product name)"
    }
    check(extractJsonLdString(brandLd, "brand") == null) { "brand object -> string-extract null" }
    check(extractJsonLdObjectField("""{"name":"商品"}""", "brand", "name") == null) {
        "missing brand object -> null"
    }
    // 単一文字列 brand は string 抽出の領分
    check(extractJsonLdString("""{"brand":"Sony"}""", "brand") == "Sony") { "string brand -> Sony" }

    println("JSON-LD STOCK: all assertions passed")
    println("ORIGIN COUNTRY: all assertions passed")
    println("GTIN/JAN: all assertions passed")
    println("AMAZON AVAILABILITY: all assertions passed")
    println("JSON-LD NUMBER: all assertions passed")
    println("JSON-LD ARRAY: all assertions passed")
    println("JSON-LD OBJECT: all assertions passed")
}
