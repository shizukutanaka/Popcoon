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

    // ── 内容量/重量 不一致ペナルティ (proto_volume_attr との parity) ──────────────
    fun vol(title: String) = ProductMatcher.extractVolume(title)
    check("extractVolume 500ml", ProductMatcher.Volume("ml", 500L), vol("洗剤 500ml"))
    check("extractVolume 2L -> 2000ml", ProductMatcher.Volume("ml", 2000L), vol("お茶 2L"))
    check("extractVolume 1リットル -> 1000ml", ProductMatcher.Volume("ml", 1000L), vol("洗剤 1リットル"))
    check("extractVolume 500g -> 500000mg", ProductMatcher.Volume("g", 500_000L), vol("コーヒー豆 500g"))
    check("extractVolume 1kg -> 1000000mg", ProductMatcher.Volume("g", 1_000_000L), vol("プロテイン 1kg"))
    check("extractVolume 500mg precision", ProductMatcher.Volume("g", 500L), vol("サプリ 500mg"))
    // 誤爆ガード
    check("5G network not volume", null, vol("SIMフリー 5G スマホ"))
    check("5ミリ length not volume", null, vol("ネジ 5ミリ 10本"))
    check("1000mAh not volume", null, vol("モバイルバッテリー 1000mAh"))
    check("model number not volume", null, vol("アイリスオーヤマ SB-2000 加湿フィルター"))
    check("ambiguous multi-liquid null", null, vol("調味料 500ml×2本 計1L"))
    // End-to-end: 内容量違いの同一ブランド商品を別 SKU として弾く
    fun pv(sku: String, platform: Platform, title: String) =
        Product(sku = sku, title = title, platform = platform, realPrice = 800, listPrice = 800)
    check("detergent 500ml vs 1L NOT matched", false,
        ProductMatcher.isMatch(
            pv("A30", Platform.AMAZON, "花王 アタック 洗濯洗剤 液体 500ml"),
            pv("R30", Platform.RAKUTEN, "花王 アタック 洗濯洗剤 液体 1L")))
    check("coffee 200g vs 500g NOT matched", false,
        ProductMatcher.isMatch(
            pv("A31", Platform.AMAZON, "スターバックス コーヒー豆 ハウスブレンド 200g"),
            pv("R31", Platform.RAKUTEN, "スターバックス コーヒー豆 ハウスブレンド 500g")))
    check("detergent same 1L IS matched (control)", true,
        ProductMatcher.isMatch(
            pv("A32", Platform.AMAZON, "花王 アタック 洗濯洗剤 液体 1L"),
            pv("Y32", Platform.YAHOO, "花王 アタック 洗濯洗剤 液体 1000ml")))

    // ── 色抽出の recall 拡張 (カーキ/アイボリー/チャコール/ワインレッド/モスグリーン等) ──
    check("extractColor カーキ -> KHAKI", "KHAKI", ProductMatcher.extractColor("ミリタリージャケット カーキ"))
    check("extractColor アイボリー -> WHITE (保守的に白へ)", "WHITE", ProductMatcher.extractColor("トートバッグ アイボリー"))
    check("extractColor チャコール -> GRAY", "GRAY", ProductMatcher.extractColor("ニット チャコール"))
    check("extractColor ワインレッド -> RED", "RED", ProductMatcher.extractColor("財布 ワインレッド"))
    check("extractColor モスグリーン -> GREEN", "GREEN", ProductMatcher.extractColor("チノパン モスグリーン"))
    check("extractColor ターコイズ -> BLUE", "BLUE", ProductMatcher.extractColor("ピアス ターコイズ"))
    check("extractColor キャメル -> BROWN", "BROWN", ProductMatcher.extractColor("コート キャメル"))
    // 独立色 カーキ は他色との不一致でペナルティ (別カラバリ=別SKU)
    fun pc(sku: String, platform: Platform, title: String) =
        Product(sku = sku, title = title, platform = platform, realPrice = 6000, listPrice = 6000)
    check("カーキ vs ブラック NOT matched (別カラバリ)", false,
        ProductMatcher.isMatch(
            pc("A40", Platform.AMAZON, "ユニクロ ミリタリージャケット カーキ M"),
            pc("R40", Platform.RAKUTEN, "ユニクロ ミリタリージャケット ブラック M")))
    // アイボリー と ホワイト は同一正準色 (WHITE)。canonical 一致=色ペナルティは発生しない
    // (保守的: 同一商品の白系表記ゆれを別 SKU と誤判定しない)。
    check("アイボリー と ホワイト は同一正準色 WHITE (色ペナルティ回避)",
        ProductMatcher.extractColor("バッグ ホワイト"), ProductMatcher.extractColor("バッグ アイボリー"))
    // recall の価値: アイボリー が WHITE として抽出されるので、他色との不一致を検出できる。
    // 共有トークンが多く色以外は同一の 2 商品を、色ペナルティ (WHITE vs BLACK) で別 SKU に分離。
    check("アイボリー vs ブラック NOT matched (色ペナルティが分離)", false,
        ProductMatcher.isMatch(
            pc("A41", Platform.AMAZON, "無印良品 撥水 トートバッグ 大容量 A4 通勤 アイボリー"),
            pc("Y41", Platform.YAHOO, "無印良品 撥水 トートバッグ 大容量 A4 通勤 ブラック")))
    // 既存の複合語誤爆防止は不変 (ブルーレイ の ブルー は色でない)
    check("ブルーレイ は色でない (回帰)", null, ProductMatcher.extractColor("ソニー ブルーレイレコーダー 2TB"))

    if (fails == 0) {
        println("PRODUCT MATCHER: all assertions passed")
    } else {
        println("PRODUCT MATCHER: $fails assertion(s) FAILED")
        kotlin.system.exitProcess(1)
    }
}
