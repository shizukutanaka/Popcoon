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

    if (fails == 0) {
        println("PRODUCT MATCHER: all assertions passed")
    } else {
        println("PRODUCT MATCHER: $fails assertion(s) FAILED")
        kotlin.system.exitProcess(1)
    }
}
