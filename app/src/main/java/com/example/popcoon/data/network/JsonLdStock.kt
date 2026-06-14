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
