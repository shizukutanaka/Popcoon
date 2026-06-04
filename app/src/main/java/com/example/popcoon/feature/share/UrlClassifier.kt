package com.example.popcoon.feature.share

import com.example.popcoon.data.model.Platform

/**
 * Share Intent から渡された URL を解析して Platform + SKU を抽出。
 *
 * 同種ソフト調査:
 *  - Pricey: 共有ボタンから 2 タップで価格比較完了 (中核体験)
 *  - 「アプリを開かなくても、共有ボタンでウォッチリスト追加」
 *
 * Popcoon 実装:
 *  - Amazon: /dp/{ASIN} or /gp/product/{ASIN}
 *  - 楽天: /{shop}/{itemcode}
 *  - Yahoo!: /shopping/store/{store}/{code}.html
 *
 * Pure function なのでテスト容易。
 */
object UrlClassifier {

    data class ClassifiedUrl(
        val platform: Platform,
        val sku: String,
        val canonicalUrl: String,
    )

    private val AMAZON_PATTERNS = listOf(
        Regex("""amazon\.co\.jp/.*?/dp/([A-Z0-9]{10})"""),
        Regex("""amazon\.co\.jp/dp/([A-Z0-9]{10})"""),
        Regex("""amazon\.co\.jp/gp/product/([A-Z0-9]{10})"""),
    )

    private val RAKUTEN_PATTERN =
        Regex("""item\.rakuten\.co\.jp/([^/]+)/([^/?]+)""")

    private val YAHOO_PATTERN =
        Regex("""store\.shopping\.yahoo\.co\.jp/([^/]+)/([^/?#]+)""")

    /**
     * URL を分類。マッチしない場合 null。
     * 入力は trim 済み URL を前提 (前後空白は呼び出し側で除去)。
     */
    fun classify(rawUrl: String): ClassifiedUrl? {
        val url = rawUrl.trim().removeQuery()

        // Amazon
        for (pattern in AMAZON_PATTERNS) {
            pattern.find(url)?.let { m ->
                val asin = m.groupValues[1]
                return ClassifiedUrl(
                    platform = Platform.AMAZON,
                    sku = asin,
                    canonicalUrl = "https://www.amazon.co.jp/dp/$asin",
                )
            }
        }

        // 楽天
        RAKUTEN_PATTERN.find(url)?.let { m ->
            val shop = m.groupValues[1]
            val item = m.groupValues[2]
            return ClassifiedUrl(
                platform = Platform.RAKUTEN,
                sku = "$shop:$item",
                canonicalUrl = "https://item.rakuten.co.jp/$shop/$item/",
            )
        }

        // Yahoo!ショッピング
        YAHOO_PATTERN.find(url)?.let { m ->
            val store = m.groupValues[1]
            val code = m.groupValues[2]
            return ClassifiedUrl(
                platform = Platform.YAHOO,
                sku = "$store:$code",
                canonicalUrl = "https://store.shopping.yahoo.co.jp/$store/$code.html",
            )
        }

        return null
    }

    /**
     * 共有された文字列から URL を抽出 (テキストに混じった URL を救出)。
     * Twitter共有などでは「商品名 + URL」形式の場合あり。
     */
    fun extractUrl(text: String): String? {
        val pattern = Regex("""https?://[^\s]+""")
        return pattern.find(text)?.value
    }

    private fun String.removeQuery(): String =
        substringBefore('?').substringBefore('#')
}
