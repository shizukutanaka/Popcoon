package io.github.shizukutanaka.popcoon.feature.matching

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product

/**
 * Standalone execution check for ProductMatcher model-number extraction.
 * No Android SDK: ProductMatcher depends only on the Product model.
 *
 * Focus: normalizeTitle() converts full-width->half-width, but extractModelNumber()
 * applied MODEL_REGEX to title.uppercase() WITHOUT that conversion, so a full-width
 * model number (common when sellers write the whole title full-width) was missed.
 */
private var fails = 0

private fun check(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        println("MISMATCH [$name]: expected=$expected actual=$actual")
        fails++
    }
}

private fun model(title: String) = ProductMatcher.extractModelNumber(title)

fun main() {
    // ── ASCII model numbers (baseline) ─────────────────────────────────────
    check("ascii WH-1000XM5", "WH1000XM5", model("ソニー WH-1000XM5 ワイヤレスヘッドホン"))
    check("ascii RTX4090", "RTX4090", model("GeForce RTX4090 搭載 PC"))
    check("no model", null, model("普通のシャンプー 詰替"))

    // ── Full-width model numbers — must also be extracted ──────────────────
    check("zenkaku ＷＦ－１０００ＸＭ４", "WF1000XM4", model("ソニー　ＷＦ－１０００ＸＭ４　ワイヤレスイヤホン"))
    check("zenkaku ＲＴＸ４０９０", "RTX4090", model("ＧｅＦｏｒｃｅ　ＲＴＸ４０９０　搭載"))
    check("zenkaku space-sep ＲＴＸ　４０９０", "RTX4090", model("ＧｅＦｏｒｃｅ　ＲＴＸ　４０９０"))

    // ── Consistency: extractModelNumber agrees with normalizeTitle on width ──
    // normalizeTitle already half-widths, so the full-width title shares a token
    // with its ASCII twin; the model path should be consistent.
    val ascii = ProductMatcher.normalizeTitle("ソニー WF-1000XM4 イヤホン")
    val zen = ProductMatcher.normalizeTitle("ソニー　ＷＦ－１０００ＸＭ４　イヤホン")
    check("normalizeTitle width-consistent (shares wf token)",
        true, ascii.intersect(zen).isNotEmpty())

    // ── End-to-end: ASCII vs full-width listing of the SAME product match ───
    // (cross-mall dedup / 名寄せ depends on this; broken before the fix.)
    fun prod(sku: String, platform: Platform, title: String) =
        Product(sku = sku, title = title, platform = platform, realPrice = 30000, listPrice = 30000)
    val asciiListing = prod("A1", Platform.AMAZON, "ソニー WF-1000XM4 ワイヤレスイヤホン")
    val zenkakuListing = prod("R1", Platform.RAKUTEN, "ソニー　ＷＦ－１０００ＸＭ４　ワイヤレスイヤホン")
    check("isMatch(ascii, full-width) same product", true,
        ProductMatcher.isMatch(asciiListing, zenkakuListing))

    // ── Half-width katakana (NFKC): ｿﾆｰ / ﾊﾞｯﾌｧﾛｰ must normalize to full-width ─
    val halfKana = prod("Y1", Platform.YAHOO, "ｿﾆｰ WF-1000XM4 ﾜｲﾔﾚｽｲﾔﾎﾝ")
    check("isMatch(full-kana, half-kana) same product", true,
        ProductMatcher.isMatch(asciiListing, halfKana))
    // 半角カナ濁点合成 ﾊﾞｯﾌｧﾛｰ -> バッファロー: トークンが全角カナ版と一致
    check("half-kana token normalizes (バッファロー)", true,
        ProductMatcher.normalizeTitle("ﾊﾞｯﾌｧﾛｰ ルーター")
            .intersect(ProductMatcher.normalizeTitle("バッファロー ルーター")).contains("バッファロー"))

    // ── Regression: different generation/capacity SKUs must not be merged ──
    // (commercial-readiness audit finding: WH-1000XM4 vs XM5 shared enough
    // marketing tokens to exceed MATCH_THRESHOLD via plain Jaccard alone;
    // iPhone 15 128GB vs 256GB both collapsed to model "IPHONE15", losing
    // the capacity distinction entirely.)
    val xm4 = prod("A9", Platform.AMAZON, "ソニー ワイヤレスノイズキャンセリングヘッドホン WH-1000XM4 ブラック Bluetooth")
    val xm5 = prod("R9", Platform.RAKUTEN, "SONY WH-1000XM5 ワイヤレスノイズキャンセリングヘッドホン ブラック Bluetooth")
    check("different generation (WH-1000XM4 vs XM5) NOT matched", false,
        ProductMatcher.isMatch(xm4, xm5))

    check("extractModelNumber concatenates adjacent capacity (iPhone 15 128GB)",
        "IPHONE15128GB", model("Apple iPhone 15 128GB"))

    val iphone128 = prod("A10", Platform.AMAZON, "Apple iPhone 15 128GB ブルー SIMフリー")
    val iphone256 = prod("R10", Platform.RAKUTEN, "Apple iPhone 15 256GB ブルー SIMフリー")
    check("different capacity (iPhone 15 128GB vs 256GB) NOT matched", false,
        ProductMatcher.isMatch(iphone128, iphone256))

    // ── 属性不一致ペナルティ (WDC corner-case precision、2026-07 リサーチ) ──
    // 個数属性: 型番一致 (SB2000) でも 2個 vs 4個 は別 SKU。
    // base = 0.7 + (4/6)*0.3 = 0.9、個数ペナルティ 0.5 → 0.45 < 0.6。
    val filter2 = prod("A11", Platform.AMAZON, "アイリスオーヤマ SB-2000 加湿フィルター 2個")
    val filter4 = prod("R11", Platform.RAKUTEN, "アイリスオーヤマ SB-2000 加湿フィルター 4個")
    check("same model different quantity (2個 vs 4個) NOT matched", false,
        ProductMatcher.isMatch(filter2, filter4))
    check("same model same quantity IS matched", true,
        ProductMatcher.isMatch(filter2, prod("Y11", Platform.YAHOO, "アイリスオーヤマ SB-2000 加湿フィルター 2個")))

    // 色属性: 型番一致 (IPHONE15128GB) でも ブルー vs レッド は別 SKU。
    // base = 0.7 + (5/7)*0.3 = 0.914...、色ペナルティ 0.6 → 0.549 < 0.6。
    val iphoneRed = prod("Y10", Platform.YAHOO, "Apple iPhone 15 128GB レッド SIMフリー")
    check("same model different color (ブルー vs レッド) NOT matched", false,
        ProductMatcher.isMatch(iphone128, iphoneRed))

    // 属性抽出の単体確認
    check("extractQuantity 24本", 24, ProductMatcher.extractQuantity("コカコーラ 500ml 24本"))
    check("extractQuantity ambiguous -> null", null,
        ProductMatcher.extractQuantity("2個セット 合計4個"))
    check("extractColor katakana", "BLUE",
        ProductMatcher.extractColor("Apple iPhone 15 128GB ブルー SIMフリー"))
    check("extractColor ブルーレイ is not a color", null,
        ProductMatcher.extractColor("ソニー ブルーレイレコーダー 2TB"))
    check("extractColor multi-color listing -> null", null,
        ProductMatcher.extractColor("iPhone ケース ブラック ホワイト 選択可"))

    // ── 研究 2-2: 文字 2-gram Dice 併用ブレンド ────────────────────────────────
    // 分かち書きなし同一商品を Jaccard 退化から救済しつつ、別カテゴリ商品は弾く。
    fun approx(name: String, expected: Double, actual: Double, tol: Double = 1e-9) {
        if (kotlin.math.abs(expected - actual) > tol) {
            println("MISMATCH [$name]: expected=$expected actual=$actual")
            fails++
        }
    }
    // 牛乳: 空白有無だけが違う同一商品 → 正規化後の 2-gram が完全一致 → 0.75×1.0
    approx("titleSimilarity milk spaceless=0.75",
        0.75, ProductMatcher.titleSimilarity("明治おいしい牛乳900ml", "明治 おいしい牛乳 900ml 送料無料"))
    // イヤホン vs ヘッドホン: raw dice は高いが減衰後は閾値未満
    check("earphone vs headphone titleSim < 0.6", true,
        ProductMatcher.titleSimilarity("ソニー ワイヤレスイヤホン", "ソニー ワイヤレスヘッドホン") < 0.6)
    // 語順入替: Jaccard=1.0 が max() で勝つ
    approx("word reorder titleSim=1.0",
        1.0, ProductMatcher.titleSimilarity("ソニー WH-1000XM5 ブラック", "ブラック WH-1000XM5 ソニー"))
    // 全く別商品 → 0
    approx("different products titleSim=0.0",
        0.0, ProductMatcher.titleSimilarity("コーヒー豆 ブラジル 500g", "ゲーミングマウス ロジクール"))
    // 空タイトルでも例外なし
    approx("empty titles titleSim=0.0", 0.0, ProductMatcher.titleSimilarity("", ""))
    // 1 文字は 2-gram を作れない
    approx("single char no bigram dice=0.0", 0.0, ProductMatcher.charBigramDice("あ", "あ"))
    // End-to-end: 分かち書きなし同一商品が isMatch=true になる (これが 2-2 の目的)
    fun p(sku: String, platform: Platform, title: String) =
        Product(sku = sku, title = title, platform = platform, realPrice = 500, listPrice = 500)
    check("spaceless same product IS matched (2-2 core)", true,
        ProductMatcher.isMatch(
            p("A20", Platform.AMAZON, "明治おいしい牛乳900ml"),
            p("R20", Platform.RAKUTEN, "明治 おいしい牛乳 900ml 送料無料")))

    if (fails == 0) {
        println("PRODUCT MATCHER: all assertions passed")
    } else {
        println("PRODUCT MATCHER: $fails assertion(s) FAILED")
        kotlin.system.exitProcess(1)
    }
}
