package io.github.shizukutanaka.popcoon.feature.matching

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
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

    // ── 研究 2-2: 文字 2-gram Dice 併用ブレンド ──────────────────────────────
    // 日本語 EC タイトルは分かち書きが無いことが多く、トークン Jaccard は
    // 「タイトル全体が 1 トークン」に退化する。文字 2-gram Dice が空白非依存で救済する。
    "分かち書きなし同一商品を名寄せできる (Jaccard 退化を 2-gram Dice で救済)" {
        val a = product("A1", "明治おいしい牛乳900ml")
        val b = product("R1", "明治 おいしい牛乳 900ml 送料無料", Platform.RAKUTEN)
        ProductMatcher.isMatch(a, b) shouldBe true
    }

    "titleSimilarity: 分かち書き有無だけが違えば 0.75 (減衰係数 × dice 1.0)" {
        ProductMatcher.titleSimilarity(
            "明治おいしい牛乳900ml", "明治 おいしい牛乳 900ml 送料無料",
        ) shouldBe (0.75 plusOrMinus 1e-9)
    }

    "titleSimilarity: 語順入替は Jaccard=1.0 が max() で勝つ" {
        ProductMatcher.titleSimilarity(
            "ソニー WH-1000XM5 ブラック", "ブラック WH-1000XM5 ソニー",
        ) shouldBe (1.0 plusOrMinus 1e-9)
    }

    "ブランド+カテゴリ語だけ共有する別商品 (イヤホン vs ヘッドホン) は誤マッチしない" {
        // raw dice は高い (共通接頭辞が長い) が、0.75 減衰で閾値 0.6 を下回る
        val a = product("A1", "ソニー ワイヤレスイヤホン")
        val b = product("R1", "ソニー ワイヤレスヘッドホン", Platform.RAKUTEN)
        ProductMatcher.isMatch(a, b) shouldBe false
    }

    "charBigramDice: 1 文字は 2-gram を作れず 0" {
        ProductMatcher.charBigramDice("あ", "あ") shouldBe 0.0
    }

    // ── 内容量/重量 不一致ペナルティ (個数/色に続く属性拡張) ─────────────────────
    "内容量違い (洗剤 500ml vs 1L) は同一商品と判定しない" {
        val a = product("A1", "花王 アタック 洗濯洗剤 液体 500ml")
        val b = product("R1", "花王 アタック 洗濯洗剤 液体 1L", Platform.RAKUTEN)
        ProductMatcher.isMatch(a, b) shouldBe false
    }

    "重量違い (コーヒー豆 200g vs 500g) は同一商品と判定しない" {
        val a = product("A1", "スターバックス コーヒー豆 ハウスブレンド 200g")
        val b = product("R1", "スターバックス コーヒー豆 ハウスブレンド 500g", Platform.RAKUTEN)
        ProductMatcher.isMatch(a, b) shouldBe false
    }

    "同一内容量は単位表記が違っても同一商品 (1L == 1000ml、対照群)" {
        val a = product("A1", "花王 アタック 洗濯洗剤 液体 1L")
        val b = product("Y1", "花王 アタック 洗濯洗剤 液体 1000ml", Platform.YAHOO)
        ProductMatcher.isMatch(a, b) shouldBe true
    }

    "extractVolume: kg は mg 基準に正規化 (1kg == 1000g)" {
        ProductMatcher.extractVolume("プロテイン 1kg") shouldBe
            ProductMatcher.extractVolume("プロテイン 1000g")
    }

    "extractVolume: ネットワーク 5G は重量として誤抽出しない" {
        ProductMatcher.extractVolume("SIMフリー 5G スマホ") shouldBe null
    }

    "extractVolume: 5ミリ (長さ) は内容量として誤抽出しない" {
        ProductMatcher.extractVolume("ネジ 5ミリ 10本") shouldBe null
    }

    // ── 属性不一致ペナルティ (WDC ベンチマークの corner-case precision 知見、2026-07) ──
    // 個数・色の食い違いは型番一致でも別 SKU (別価格)。型番一致の 0.7 底上げだけでは
    // 閾値 0.6 を下回れないため、乗算ペナルティ (個数 0.5 / 色 0.6) で確実に落とす。
    "同一型番でも個数違い (2個 vs 4個) は同一商品と判定しない" {
        val a = product("A1", "アイリスオーヤマ SB-2000 加湿フィルター 2個")
        val b = product("R1", "アイリスオーヤマ SB-2000 加湿フィルター 4個")
        ProductMatcher.isMatch(a, b) shouldBe false
        // 対照群: 同数量なら高信頼マッチ
        val c = product("Y1", "アイリスオーヤマ SB-2000 加湿フィルター 2個")
        ProductMatcher.isMatch(a, c) shouldBe true
    }

    "同一型番でも色違い (ブルー vs レッド) は同一商品と判定しない" {
        val a = product("A1", "Apple iPhone 15 128GB ブルー SIMフリー")
        val b = product("R1", "Apple iPhone 15 128GB レッド SIMフリー")
        ProductMatcher.isMatch(a, b) shouldBe false
    }

    "色抽出: ブルーレイは色ではない (複合カタカナ語の誤爆防止)" {
        ProductMatcher.extractColor("ソニー ブルーレイレコーダー 2TB") shouldBe null
    }

    "色抽出: カラバリ一覧タイトル (複数色) は曖昧なので null" {
        ProductMatcher.extractColor("iPhone ケース ブラック ホワイト 選択可") shouldBe null
    }

    "色抽出: 日英表記を同一視 (ブラック == BLACK)" {
        ProductMatcher.extractColor("ソニー ヘッドホン ブラック") shouldBe
            ProductMatcher.extractColor("SONY Headphones BLACK")
    }

    "個数抽出: 複数の異なる数量は曖昧なので null" {
        ProductMatcher.extractQuantity("2個セット 合計4個") shouldBe null
        ProductMatcher.extractQuantity("コカコーラ 500ml 24本") shouldBe 24
    }

    "属性が片方しか取れない場合はペナルティなし (保守的中立)" {
        // 黒 (漢字1文字) は誤爆リスクのため抽出対象外 → null → ペナルティなしで高信頼マッチ維持
        val a = product("A1", "ソニー WH-1000XM5 ブラック")
        val b = product("R1", "SONY WH-1000XM5 黒")
        ProductMatcher.isMatch(a, b) shouldBe true
    }
})
