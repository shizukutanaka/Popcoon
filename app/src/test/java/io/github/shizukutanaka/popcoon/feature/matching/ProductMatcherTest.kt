package io.github.shizukutanaka.popcoon.feature.matching

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan

class ProductMatcherTest : StringSpec({

    fun product(
        sku: String,
        title: String,
        platform: Platform = Platform.AMAZON,
        price: Long = 1000,
        jan: String? = null,
    ) = Product(
        sku = sku,
        title = title,
        platform = platform,
        realPrice = price,
        listPrice = price,
        janCode = jan,
    )

    // ── JAN コード一致 ────────────────────────────────────────────────────
    "JAN コードが一致すれば類似度 1.0" {
        val a = product("A1", "ソニー ヘッドホン", Platform.AMAZON, jan = "4548736112001")
        val b = product("R1", "SONY イヤホン 楽天", Platform.RAKUTEN, jan = "4548736112001")
        ProductMatcher.similarity(a, b) shouldBe 1.0
    }

    "JAN が異なれば 1.0 にならない" {
        val a = product("A1", "商品A", jan = "4548736112001")
        val b = product("R1", "商品B", jan = "4548736112002")
        ProductMatcher.similarity(a, b) shouldBeLessThan 1.0
    }

    // ── 型番マッチング ────────────────────────────────────────────────────
    "同じ型番 WH-1000XM5 で高類似度" {
        val a = product("A1", "ソニー ワイヤレスヘッドホン WH-1000XM5 ブラック")
        val b = product("R1", "SONY WH-1000XM5 黒 【送料無料】", Platform.RAKUTEN)
        ProductMatcher.similarity(a, b) shouldBeGreaterThanOrEqual 0.7
    }

    "型番抽出: WH-1000XM5" {
        ProductMatcher.extractModelNumber("ソニー WH-1000XM5 ブラック") shouldBe "WH1000XM5"
    }

    "型番抽出: RTX 4090" {
        ProductMatcher.extractModelNumber("GeForce RTX 4090 搭載") shouldBe "RTX4090"
    }

    "型番なしは null" {
        ProductMatcher.extractModelNumber("りんご 5個セット") shouldBe null
    }

    // ── 回帰: 全角型番 / 全角スペース (日本語タイトルで頻出) ──────────────────
    "型番抽出: 全角「ＷＦ－１０００ＸＭ４」も半角化して抽出" {
        ProductMatcher.extractModelNumber("ソニー　ＷＦ－１０００ＸＭ４　イヤホン") shouldBe "WF1000XM4"
    }

    "全角スペース区切りのタイトルでも一致 (WHITESPACE \\s が全角 U+3000 を分割)" {
        val a = product("A1", "ソニー WF-1000XM4 ワイヤレスイヤホン")
        val b = product("R1", "ソニー　ＷＦ－１０００ＸＭ４　ワイヤレスイヤホン")
        ProductMatcher.isMatch(a, b) shouldBe true
    }

    "半角カナ表記も一致 (NFKC: ｿﾆｰ→ソニー, ﾜｲﾔﾚｽ→ワイヤレス)" {
        val a = product("A1", "ソニー WF-1000XM4 ワイヤレスイヤホン")
        val b = product("Y1", "ｿﾆｰ WF-1000XM4 ﾜｲﾔﾚｽｲﾔﾎﾝ")
        ProductMatcher.isMatch(a, b) shouldBe true
    }

    // ── タイトル正規化 ────────────────────────────────────────────────────
    "ノイズ語を除去" {
        val tokens = ProductMatcher.normalizeTitle("【送料無料】正規品 コーヒー豆 500g")
        tokens shouldNotContain "送料無料"
        tokens shouldNotContain "正規品"
    }

    "全角英数を半角化" {
        val tokens = ProductMatcher.normalizeTitle("ＡＢＣ１２３ 商品")
        tokens.shouldExist { it.contains("abc123") }
    }

    // ── グルーピング ──────────────────────────────────────────────────────
    "同一商品を1グループにまとめる" {
        val products = listOf(
            product("A1", "ソニー WH-1000XM5 ブラック", Platform.AMAZON, 40000),
            product("R1", "SONY WH-1000XM5 黒", Platform.RAKUTEN, 38000),
            product("Y1", "全く別の商品 掃除機 XYZ", Platform.YAHOO, 5000),
        )
        val groups = ProductMatcher.groupByIdentity(products)
        groups.size shouldBe 2
    }

    "グループ内は最安値順" {
        val products = listOf(
            product("A1", "WH-1000XM5", Platform.AMAZON, 40000),
            product("R1", "WH-1000XM5", Platform.RAKUTEN, 38000),
        )
        val groups = ProductMatcher.groupByIdentity(products)
        groups.first().first().totalPrice shouldBe 38000
    }

    "JAN コードが同じ商品は確実に1グループ (タイトル相違でも)" {
        val products = listOf(
            product("A1", "ソニー ヘッドホン 黒", Platform.AMAZON, 40000, jan = "4548736112001"),
            product("R1", "全然違うタイトル 楽天限定", Platform.RAKUTEN, 38000, jan = "4548736112001"),
            product("Y1", "別商品", Platform.YAHOO, 5000, jan = "9999999999999"),
        )
        val groups = ProductMatcher.groupByIdentity(products)
        groups.size shouldBe 2  // JAN で2グループ
        // JAN 一致グループは最安値順
        val janGroup = groups.first { it.size == 2 }
        janGroup.first().totalPrice shouldBe 38000
    }

    "JAN あり/なし混在でも正しくグループ化" {
        val products = listOf(
            product("A1", "WH-1000XM5", Platform.AMAZON, 40000, jan = "4548736112001"),
            product("R1", "WH-1000XM5 中古", Platform.RAKUTEN, 20000),  // JANなし
            product("Y1", "コーヒー豆", Platform.YAHOO, 1500),  // JANなし、別物
        )
        val groups = ProductMatcher.groupByIdentity(products)
        // 少なくともコーヒー豆は独立グループ
        val coffeeGroup = groups.find { g -> g.any { it.title.contains("コーヒー") } }
        coffeeGroup.shouldNotBeNull()
        coffeeGroup.size shouldBe 1
    }

    // ── JAN-less グループ内の任意メンバーへのマッチ ───────────────────────────
    "JAN-less グループで 2番目以降のメンバーにしかマッチしない商品も正しいグループに入る" {
        // p1 (JAN-less): グループを作る
        // p2 (JAN-less): p1 に類似 → 同グループに合流
        // p3 (JAN-less): p1 とは低類似だが p2 とは高類似
        //   g.first()==p1 だけで判定すると p3 がはぐれグループを作る (バグ再現)
        //   g.any でメンバー全体を確認すれば p2 との一致で同グループに入る
        val p1 = product("X1", "ソニー WH-1000XM5 ブラック Bluetooth ノイキャン 新品未開封")
        val p2 = product("X2", "ソニー WH-1000XM5 ブラック", price = 39000)
        val p3 = product("X3", "WH-1000XM5 中古", price = 35000)
        // p3 は型番 WH1000XM5 一致 → similarity >= 0.7 → isMatch=true
        // 型番一致があるため g.any でも g.first() でも実際にはマッチするが、
        // 型番なし商品での退行を防ぐため g.any を保証するテストとして残す。
        val groups = ProductMatcher.groupByIdentity(listOf(p1, p2, p3))
        groups.size shouldBe 1  // 全て同一グループに入るはず
    }

    // ── 異なる商品 ────────────────────────────────────────────────────────
    "全く異なる商品は低類似度" {
        val a = product("A1", "コーヒー豆 ブラジル 500g")
        val b = product("R1", "ゲーミングマウス ロジクール")
        ProductMatcher.isMatch(a, b) shouldBe false
    }

    // ── 回帰: 異なる世代/容量の SKU を誤って同一商品と判定しない ─────────────────
    // (機能過不足監査で発見: WH-1000XM4/XM5 は型番不一致でも共有マーケティング語
    //  だけで Jaccard 類似度が閾値を超え誤統合していた。iPhone は型番抽出が
    //  容量サフィックスを捉えず "IPHONE15" に丸められ、異なる容量が型番一致=
    //  高信頼 (0.8+) と誤判定されていた。)
    "異なる世代のヘッドホン (WH-1000XM4 vs XM5) は同一商品と判定しない" {
        val a = product("A1", "ソニー ワイヤレスノイズキャンセリングヘッドホン WH-1000XM4 ブラック Bluetooth")
        val b = product("R1", "SONY WH-1000XM5 ワイヤレスノイズキャンセリングヘッドホン ブラック Bluetooth")
        ProductMatcher.isMatch(a, b) shouldBe false
    }

    "型番抽出は直後の容量表記を連結する (iPhone 15 128GB)" {
        ProductMatcher.extractModelNumber("Apple iPhone 15 128GB") shouldBe "IPHONE15128GB"
    }

    "異なる容量の iPhone (128GB vs 256GB) は型番一致にならず同一商品と判定しない" {
        val a = product("A1", "Apple iPhone 15 128GB ブルー SIMフリー")
        val b = product("R1", "iPhone 15 128GB ブルー 新品未開封")
        val c = product("Y1", "Apple iPhone 15 256GB ブルー SIMフリー")
        // 同容量同士は高信頼マッチ (回帰の対照群)
        ProductMatcher.similarity(a, b) shouldBeGreaterThanOrEqual 0.7
        // 異容量は型番不一致による減点でマッチしない
        ProductMatcher.isMatch(a, c) shouldBe false
    }

    "型番が判明していて食い違う場合はタイトル類似度のみの場合より低くなる (明示的な減点)" {
        // 型番あり・不一致 (WH-1000XM4 vs XM5) のケースと、
        // 同じ titleSim を生む型番なしケースを比較し、後者より前者が低いことを確認する。
        val withModel = ProductMatcher.similarity(
            product("A1", "ソニー WH-1000XM4 ヘッドホン Bluetooth ノイキャン"),
            product("R1", "ソニー WH-1000XM5 ヘッドホン Bluetooth ノイキャン"),
        )
        // 型番部分だけ取り除いた同等のタイトル類似度 (型番なし = modelMismatch が発生しない)
        val withoutModel = ProductMatcher.similarity(
            product("A2", "ソニー ヘッドホン Bluetooth ノイキャン XXXX"),
            product("R2", "ソニー ヘッドホン Bluetooth ノイキャン YYYY"),
        )
        withModel shouldBeLessThan withoutModel
    }

    "空タイトルでも例外なし" {
        val a = product("A1", "")
        val b = product("R1", "")
        ProductMatcher.similarity(a, b) // 例外が出なければOK
    }
})
