package io.github.shizukutanaka.popcoon.core

/**
 * DeepLinks の実行検証ハーネス (Android SDK 不要)。
 * run_deeplinks.sh から DeepLinks.kt と一緒にコンパイル・実行する。
 *
 * 主眼: プロデューサ (product) とコンシューマ (productKeyOrNull) の**ラウンドトリップ性**。
 * これが成り立てば、通知/ウィジェット側の生成と MainActivity 側の解析が同じ定義から導かれ、
 * 片側だけ scheme/path を変えて黙って壊れる継ぎ目バグを排除できる。
 */
fun main() {
    var fails = 0
    fun check(name: String, cond: Boolean) {
        if (!cond) { println("MISMATCH [$name]"); fails++ }
    }

    // ラウンドトリップ: product(key) を productKeyOrNull で戻すと元のキーに一致する。
    val keys = listOf(
        "amazon:B0TEST001",
        "rakuten:shop:item-123",     // コロンを含む複合キー
        "yahoo:store/itemcode",      // スラッシュを含む
        "amazon:あいう",              // 日本語
        "",                           // 空キー (プレフィックスのみ)
    )
    for (k in keys) {
        val uri = DeepLinks.product(k)
        check("roundtrip[$k]", DeepLinks.productKeyOrNull(uri) == k)
        check("prefix[$k]", uri.startsWith(DeepLinks.PRODUCT_PREFIX))
    }

    // 生成形式が従来契約 (popcoon://product/{key}) と一致 (NotificationLogicTest と同値)。
    check("format", DeepLinks.product("amazon:B0TEST001") == "popcoon://product/amazon:B0TEST001")

    // 非商品リンクは productKeyOrNull が null を返す (取り違え防止)。
    check("barcode->null", DeepLinks.productKeyOrNull(DeepLinks.BARCODE) == null)
    check("watchlist->null", DeepLinks.productKeyOrNull(DeepLinks.WATCHLIST) == null)
    check("search->null", DeepLinks.productKeyOrNull("popcoon://search?q=tv") == null)
    check("foreign->null", DeepLinks.productKeyOrNull("https://amazon.co.jp/dp/B0") == null)

    // 判定述語の相互排他性 (コンシューマ側 when の分岐が衝突しないこと)。
    check("isBarcode", DeepLinks.isBarcode(DeepLinks.BARCODE) && !DeepLinks.isBarcode(DeepLinks.WATCHLIST))
    check("isWatchlist", DeepLinks.isWatchlist(DeepLinks.WATCHLIST) && !DeepLinks.isWatchlist(DeepLinks.BARCODE))
    check("isSearch", DeepLinks.isSearch("popcoon://search?q=x") && !DeepLinks.isSearch(DeepLinks.BARCODE))
    // 商品リンクはどの非商品述語にも該当しない。
    val prod = DeepLinks.product("amazon:X")
    check("product not others", !DeepLinks.isBarcode(prod) && !DeepLinks.isWatchlist(prod) && !DeepLinks.isSearch(prod))

    if (fails == 0) println("DEEP LINKS: all assertions passed (producer/consumer round-trip)")
    else { println("DEEP LINKS: $fails assertion(s) FAILED"); kotlin.system.exitProcess(1) }
}
