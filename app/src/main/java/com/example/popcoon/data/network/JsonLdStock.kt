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
