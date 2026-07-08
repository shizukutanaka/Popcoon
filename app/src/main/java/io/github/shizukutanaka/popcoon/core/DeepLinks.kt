package io.github.shizukutanaka.popcoon.core

/**
 * アプリ内ディープリンクの単一の真実源 (pure, Android 非依存)。
 *
 * 背景 (プロデューサ/コンシューマの継ぎ目): ディープリンク文字列は
 *  - プロデューサ: 通知 (`LocalNotificationManager.deepLinkUri`) / ウィジェット / ショートカット
 *  - コンシューマ: `MainActivity` のインテント解釈
 * の双方に散在し、片側だけ scheme/path を変えると黙って壊れる構造だった。
 * ここに集約し、生成 (`product`) と解析 (`productKeyOrNull` 等) を同じ定義から導く。
 * 純粋なのでラウンドトリップ性 (product → productKeyOrNull == 元キー) を単体検証できる。
 */
object DeepLinks {
    const val SCHEME = "popcoon"
    const val BARCODE = "popcoon://barcode"
    const val WATCHLIST = "popcoon://watchlist"
    const val SEARCH = "popcoon://search"
    const val PRODUCT_PREFIX = "popcoon://product/"

    /** productKey から商品詳細ディープリンクを生成する (プロデューサ側)。 */
    fun product(productKey: String): String = "$PRODUCT_PREFIX$productKey"

    /** 商品ディープリンクなら productKey を返す。違えば null (コンシューマ側)。 */
    fun productKeyOrNull(uri: String): String? =
        if (uri.startsWith(PRODUCT_PREFIX)) uri.removePrefix(PRODUCT_PREFIX) else null

    fun isBarcode(uri: String): Boolean = uri == BARCODE
    fun isWatchlist(uri: String): Boolean = uri == WATCHLIST
    fun isSearch(uri: String): Boolean = uri.startsWith(SEARCH)
}
