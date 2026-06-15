package com.example.popcoon.data.network

/**
 * schema.org の Offer.availability (JSON-LD) を Product.stockCount に変換する純粋関数。
 *
 * ktor 非依存に独立させ、Android SDK 無しでコンパイル・実行検証できる
 * (popcoon-tdd/kotlin_parity/run_jsonld.sh)。FallbackScraper の parseProductSchema から使う。
 *
 * schema.org の在庫切れ系の値 (OutOfStock / SoldOut / Discontinued) を 0 とみなし、
 * SortAndFilter の在庫切れ除外を機能させる。InStock 等・不明は null (= 在庫あり扱い)。
 * 値は "OutOfStock" でも "https://schema.org/OutOfStock" のような URL 形式でも受け付ける。
 */
internal fun stockFromAvailability(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val token = raw.substringAfterLast('/').trim().lowercase()  // URL 形式なら末尾要素
    return when (token) {
        "outofstock", "soldout", "discontinued" -> 0
        else -> null
    }
}

/**
 * 原産国の表記ゆれ (ISO-2/ISO-3/英語名/日本語名) を EcoEthicsScorer が期待する ISO-2 キー
 * (JP/DE/US/CN/VN/BD/IN/KR) に正規化する。対応外・不明は null。
 *
 * 背景 (プロデューサ/コンシューマのキー不一致): EcoEthicsScorer は `CO2_BY_COUNTRY[origin]` の
 * 文字列キー検索だが、データ層は originCountry を **一切設定していなかった** (eco 機能が実データで死亡)。
 * JSON-LD の schema.org `countryOfOrigin` から拾い、ここで正規化して初めて eco スコアが動く。
 * 正規化が無いと "Japan"/"日本"/"JPN" がキー "JP" に一致せず、既定値 0.60 に黙って落ちる。
 */
internal fun normalizeOriginCountry(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when (raw.substringAfterLast('/').trim().lowercase()) {
        "jp", "jpn", "japan", "日本", "にほん", "ニホン" -> "JP"
        "de", "deu", "ger", "germany", "ドイツ", "独", "独逸" -> "DE"
        "us", "usa", "united states", "united states of america", "america",
        "アメリカ", "米国", "米" -> "US"
        "cn", "chn", "china", "中国", "中華人民共和国", "中" -> "CN"
        "vn", "vnm", "vietnam", "viet nam", "ベトナム", "越南" -> "VN"
        "bd", "bgd", "bangladesh", "バングラデシュ" -> "BD"
        "in", "ind", "india", "インド", "印度" -> "IN"
        "kr", "kor", "korea", "south korea", "republic of korea",
        "韓国", "大韓民国", "韓" -> "KR"
        else -> null
    }
}

/**
 * schema.org `gtin13`/`gtin`/`gtin8` (= JAN/EAN/UPC バーコード) を Product.janCode 用に正規化する。
 * 数字のみ・長さ 8/12/13/14 桁 (JAN-8 / UPC-12 / JAN-13 / GTIN-14) なら採用、それ以外は null。
 *
 * 背景 (もう一つの死んだ継ぎ目): ProductMatcher は janCode 一致を**最優先の確実シグナル** (similarity=1.0)
 * として使うが、どのプロデューサも janCode を設定しておらず、その経路は常に死んでいた。
 * JSON-LD の gtin から復元して初めて横断名寄せの「バーコード完全一致」が機能する。
 */
internal fun normalizeGtin(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val digits = raw.trim().filter { it in '0'..'9' }  // ASCII 数字のみ (ハイフン等を除去)
    return when (digits.length) {
        8, 12, 13, 14 -> digits
        else -> null
    }
}

/**
 * Amazon PA-API 5.0 `Offers.Listings.Availability` (Type + Message) を Product.stockCount に変換する。
 *
 * 背景 (三つ目の死んだ継ぎ目): stockCount は Rakuten/Yahoo では availability/inStock から復元済みだが、
 * **最大プラットフォームの Amazon 経路では常に null** で、在庫切れ除外フィルタ・在庫アラートが
 * Amazon 商品に対して死蔵していた。PA-API は Availability を返すのに従来 DTO で取りこぼしていた。
 *
 * 在庫切れ (Type=OutOfStock/SoldOut、または Message に明確な在庫切れ語) のみ 0。
 * 「通常 N 日以内に発送」等のバックオーダー表記は在庫あり扱いで null (Rakuten/Yahoo と同じ保守方針)。
 * amazon.co.jp は Message を日本語で返すため和英両方の語を判定する。
 */
internal fun stockFromAmazonAvailability(type: String?, message: String?): Int? {
    val t = type?.trim()?.lowercase().orEmpty()
    if (t == "outofstock" || t == "soldout") return 0
    val m = message?.trim()?.lowercase().orEmpty()  // 日本語は lowercase 無影響
    if (m.isEmpty()) return null
    val outOfStockMarkers = listOf(
        "在庫切れ", "在庫なし", "入荷未定", "お取り扱いできません", "取り扱いを終了",
        "unavailable", "out of stock",
    )
    return if (outOfStockMarkers.any { m.contains(it) }) 0 else null
}
