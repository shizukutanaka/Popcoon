package io.github.shizukutanaka.popcoon.feature.tco

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/**
 * TCOCalculator テスト。
 *
 * Python 仕様 oracle (popcoon_core.py::calculate_tco) との整合確認。
 * 競合 14 アプリで非搭載の独自機能なのでテスト密度を高くする。
 */
class TCOCalculatorTest : StringSpec({

    "インクジェットプリンター 5年 TCO: 購入価格 + 消耗品 + 電気代" {
        val r = TCOCalculator.calculate(
            purchasePrice = 15_000,
            category = "inkjet_printer",
            years = 5,
        )
        r.purchasePrice shouldBe 15_000L
        r.consumablesTotal shouldBeGreaterThan 0L
        r.energyTotal shouldBeGreaterThan 0L
        r.totalTco shouldBeGreaterThan r.purchasePrice
    }

    "レーザープリンター: トナー + ドラム + 用紙" {
        val r = TCOCalculator.calculate(25_000, "laser_printer", 5)
        r.consumablesTotal shouldBeGreaterThan 0L
        // 消耗品はインクジェットの方が高い。係数表から: インクジェット 21,200 円/年
        // (インク黒 10,800 + カラー 8,800 + 用紙 1,600) > レーザー 13,440 円/年
        // (トナー 9,000 + ドラム 2,640 + 用紙 1,800)。旧アサーションは不等号が逆で、
        // 「インク代が高い」という本製品のインクタンク式比較機能の前提とも矛盾していた
        // (kotest シム初回実行で発覚)。
        val inkjet = TCOCalculator.calculate(25_000, "inkjet_printer", 5)
        inkjet.consumablesTotal shouldBeGreaterThan r.consumablesTotal
    }

    // 回帰: ドラムは使用強度 (intensity) で増減しない (0.33/年 固定)。Python 参照と一致。
    "レーザー: intensity=2.0 でもドラムはスケールしない (差分パリティで検出した実バグ)" {
        // toner=int(6000*3.0)=18000, drum=int(8000*0.33)=2640, paper=int(600*6.0)=3600 → 24240/年 ×5
        val r = TCOCalculator.calculate(40_000, "laser_printer", 5, intensity = 2.0)
        r.consumablesTotal shouldBe 121_200L
    }

    "ノート PC: 消耗品なし、電気代のみ" {
        val r = TCOCalculator.calculate(150_000, "laptop", 5)
        r.consumablesTotal shouldBe 0L
        r.energyTotal shouldBeGreaterThan 0L
    }

    "冷蔵庫は24時間稼働のため laptop(6h/45W) より電気代が高い" {
        val laptop = TCOCalculator.calculate(100_000, "laptop", 5)
        val fridge = TCOCalculator.calculate(100_000, "refrigerator", 5)
        // 35W × 24h vs 45W × 6h — 総wh は冷蔵庫(840/日) > laptop(270/日)
        fridge.energyTotal shouldBeGreaterThan laptop.energyTotal
    }

    "エアコン: 高消費電力" {
        val r = TCOCalculator.calculate(80_000, "air_conditioner", 5)
        r.energyTotal shouldBeGreaterThan 0L
        // 700W × 8h → 最も電気代が高いはず
        val laptop = TCOCalculator.calculate(80_000, "laptop", 5)
        r.energyTotal shouldBeGreaterThan laptop.energyTotal
    }

    "コーヒーカプセル: 365日 × 80円 × intensity" {
        val r = TCOCalculator.calculate(20_000, "coffee_capsule", 1)
        // 80 × 365 × 1.0 = 29,200
        r.consumablesTotal shouldBe 29_200L
    }

    "未知カテゴリ: 消耗品・電気代 0、購入価格 + 保守費のみ" {
        val r = TCOCalculator.calculate(50_000, "unknown_device", 5)
        r.consumablesTotal shouldBe 0L
        r.energyTotal shouldBe 0L
        // years=5 は保守費バンド 4..6 に入るため purchasePrice/10 = 5,000 が乗る。
        // 旧期待値 50,000 は保守費を無視しており、オラクル (calculate_tco) に対して
        // 一度も成立したことがなかった (kotest シム初回実行で発覚)。
        // 導出: 50,000 + 0 (消耗品) + 0 (電気) + 5,000 (保守) − 0 (残価 5% − 5×1% = 0) = 55,000
        r.maintenance shouldBe 5_000L
        r.totalTco shouldBe 55_000L
    }

    "intensity 2.0: 消耗品が倍増" {
        val normal = TCOCalculator.calculate(15_000, "inkjet_printer", 5, intensity = 1.0)
        val heavy = TCOCalculator.calculate(15_000, "inkjet_printer", 5, intensity = 2.0)
        heavy.consumablesTotal shouldBeGreaterThan normal.consumablesTotal
    }

    // ⚠️ このテストは以前「ドラムも intensity に比例 (×2)」を主張していたが、それは
    // 差分パリティで検出・修正済みの**旧バグの挙動**で、同ファイルの
    // 「レーザー: intensity=2.0 でもドラムはスケールしない」(121,200 を固定) と真っ向から
    // 矛盾していた。両テストは同時に成立し得ない — kotest が一度も実行されて
    // いなかったため矛盾したまま同居できていた (kotest シム初回実行で発覚)。
    // 現仕様 (Python calculate_tco と一致): ドラムは 0.33/年 固定で intensity 非適用。
    // 導出 (25,000, years=1): i=1.0 → 9,000+2,640+1,800 = 13,440
    //                         i=2.0 → 18,000+2,640+3,600 = 24,240 (≠ 13,440×2 = 26,880)
    "レーザープリンター intensity 2.0: ドラムだけは比例しない (drum bug の修正を固定)" {
        val normal = TCOCalculator.calculate(25_000, "laser_printer", 1, intensity = 1.0)
        val heavy = TCOCalculator.calculate(25_000, "laser_printer", 1, intensity = 2.0)
        normal.consumablesTotal shouldBe 13_440L
        heavy.consumablesTotal shouldBe 24_240L
    }

    "tcoPerMonth は totalTco / (years × 12)" {
        val r = TCOCalculator.calculate(12_000, "laptop", 2)
        r.tcoPerMonth shouldBe r.totalTco / (2 * 12)
    }

    "years = 0 は IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            TCOCalculator.calculate(10_000, "laptop", 0)
        }
    }

    "intensity = 0 は IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            TCOCalculator.calculate(10_000, "laptop", 5, intensity = 0.0)
        }
    }

    "purchasePrice 0 でも例外なし" {
        val r = TCOCalculator.calculate(0, "inkjet_printer", 1)
        r.purchasePrice shouldBe 0L
        r.totalTco shouldBeGreaterThan 0L  // 消耗品 + 電気代がある
    }

    "property: totalTco >= 0 (残存価値控除後も非負)" {
        checkAll(
            Arb.long(0L..1_000_000L),
            Arb.int(1..20),
        ) { price, years ->
            val r = TCOCalculator.calculate(price, "laptop", years)
            r.totalTco shouldBeGreaterThanOrEqualTo 0L
        }
    }

    // ── inferCategory (タイトル → カテゴリ推定) ──────────────────────────
    "インクジェットプリンターを推定" {
        TCOCalculator.inferCategory("キヤノン インクジェットプリンター PIXUS") shouldBe "inkjet_printer"
    }

    "レーザープリンターを推定" {
        TCOCalculator.inferCategory("ブラザー レーザープリンター モノクロ") shouldBe "laser_printer"
    }

    "ノートパソコンを推定" {
        TCOCalculator.inferCategory("ノートパソコン 15.6インチ") shouldBe "laptop"
    }

    "冷蔵庫を推定" {
        TCOCalculator.inferCategory("パナソニック 冷蔵庫 500L") shouldBe "refrigerator"
    }

    "TCO 非対象商品は null" {
        TCOCalculator.inferCategory("ワイヤレスイヤホン WH-1000XM5") shouldBe null
    }

    // 回帰: RESIDUAL_RATE_DB には元々 "smartphone" の残存価値式が存在したが、
    // inferCategory がスマホを一切検出しないため実商品では到達不能だった
    // (機能過不足監査で発見)。
    "スマートフォンを推定 (旧実装では未検出で残存価値が死んでいた)" {
        TCOCalculator.inferCategory("Apple iPhone 15 128GB ブルー SIMフリー") shouldBe "smartphone"
    }

    // 回帰: 付属品・消耗品・別ジャンルへの誤検出。TCO の電力/消耗品は購入価格と
    // 独立した実額なので、誤検出すると表示が桁で壊れる。旧実装では例えば
    //  「エアコン洗浄スプレー ¥980」 → air_conditioner → 電力 5 年 275,940 円 (本体価格の 283 倍)
    //  「サプリメント カプセル ¥1,500」 → coffee_capsule → カプセル代 146,000 円
    //  「3Dプリンター」 → inkjet_printer → 無関係なインク代 106,000 円
    // が実際に ProductDetailScreen に出ていた。Python オラクル
    // (popcoon_core.infer_tco_category) と kotlin_parity の TCOCAT で両言語同時に固定。
    listOf(
        "エアコン洗浄スプレー 3本セット",
        "エアコン用 リモコン 汎用",
        "エアコン 標準取付工事",
        "冷蔵庫マット 透明 Mサイズ",
        "冷蔵庫用 脱臭剤 3個パック",
        "iPhone 15 ケース 耐衝撃 クリア",
        "スマホスタンド 折りたたみ アルミ",
        "スマホリング 落下防止",
        "ノートパソコン スタンド 角度調整",
        "プリンターインク 互換カートリッジ 4色セット",
        "プリンター用紙 A4 500枚",
        "3Dプリンター FDM 高精度 組立済み",
        "ラベルプリンター テプラ PRO SR170",
        "感熱式 レシートプリンター 80mm",
        "カーエアコン ガス R134a 2本",
        "Android タブレット 10インチ Wi-Fiモデル",
        "スマートウォッチ Android iPhone対応",
        "サプリメント カプセル 120粒 ビタミンD",
        "ガチャガチャ カプセルトイ 空カプセル 50個",
        "洗濯洗剤 ジェルボール カプセル 詰め替え",
        "ネスプレッソ カプセル 50個入り 詰め合わせ",
    ).forEach { title ->
        "付属品・別ジャンルは TCO 対象外: $title" {
            TCOCalculator.inferCategory(title) shouldBe null
        }
    }

    "カプセル式コーヒーメーカー本体は検出する (棄却しすぎていないことの確認)" {
        TCOCalculator.inferCategory("ネスプレッソ コーヒーメーカー エッセンサミニ") shouldBe "coffee_capsule"
    }

    "エアコン本体は検出する (カーエアコン除外が本体を巻き込まないこと)" {
        TCOCalculator.inferCategory("ダイキン エアコン 6畳 S223ATES") shouldBe "air_conditioner"
    }

    "inferCategory の戻り値は calculate が知るカテゴリのみ" {
        listOf("プリンター", "レーザープリンター", "iPhone", "ノートpc", "冷蔵庫",
            "エアコン", "コーヒーメーカー").forEach { title ->
            val category = TCOCalculator.inferCategory(title)
            category shouldNotBe null
            // 未知カテゴリなら consumables/energy/residual が全て generic に落ちて
            // 購入価格そのものになる。そうなっていないことで対応表の存在を確認する。
            val r = TCOCalculator.calculate(100_000, category!!, years = 1)
            (r.consumablesTotal + r.energyTotal + r.residualValue) shouldBeGreaterThan 0L
        }
    }

    "inferCategory はどんな入力でも例外なし" {
        checkAll(Arb.string(0..100)) { s ->
            TCOCalculator.inferCategory(s)
        }
    }

    // 回帰: 5年固定だと smartphone/laptop の残存価値が常に 0 になり機能が死蔵していた。
    // 短い保有年数なら現実的な残存価値が出ることを確認する。
    "スマートフォンは2年保有なら残存価値が非0 (5年固定では常に0だった旧実装の回帰)" {
        val r = TCOCalculator.calculate(120_000, "smartphone", years = 2)
        // residualRate = max(0, 0.5 - 2*0.12) = 0.26 → residual = 120,000*0.26 = 31,200
        r.residualValue shouldBe 31_200L
    }

    "ノートPCは3年保有なら残存価値が非0" {
        val r = TCOCalculator.calculate(200_000, "laptop", years = 3)
        // residualRate = max(0, 0.4 - 3*0.08) = 0.16 → residual = 200,000*0.16 = 32,000
        r.residualValue shouldBe 32_000L
    }

    // ── vsAlternative (Python: TCOResult.vs_alternative) ──────────────────
    // 機能過不足監査で発見: Python oracle には既に存在した (calculate_tco の vs_alt)
    // フィールドが Kotlin 実装に移植されておらず、インクジェット vs インクタンク式の
    // 比較 (ダークパターン対抗の中核機能) が UI に一切表示されていなかった。
    "インクジェットは vsAlternative を持ち、Python oracle と完全一致 (8000円/5年)" {
        // Python: calculate_tco(8000, "inkjet_printer", years=5).vs_alternative
        //   == ("インクタンク式", 49000, 66165)  (total_tco=115165)
        val r = TCOCalculator.calculate(8_000, "inkjet_printer", years = 5)
        r.totalTco shouldBe 115_165L
        val alt = r.vsAlternative
        alt shouldNotBe null
        alt!!.kind shouldBe TCOCalculator.AlternativeKind.INK_TANK_PRINTER
        alt.altTco shouldBe 49_000L
        alt.savings shouldBe 66_165L
        alt.savings shouldBeGreaterThan 10_000L
    }

    "インクジェット以外は vsAlternative が null" {
        TCOCalculator.calculate(150_000, "laptop", years = 5).vsAlternative shouldBe null
        TCOCalculator.calculate(25_000, "laser_printer", years = 5).vsAlternative shouldBe null
        TCOCalculator.calculate(50_000, "unknown_device", years = 5).vsAlternative shouldBe null
    }

    "vsAlternative.altTco は購入価格×3 + 1万円 + 3千円×年数 (Python と同一式)" {
        val r = TCOCalculator.calculate(15_000, "inkjet_printer", years = 3)
        r.vsAlternative!!.altTco shouldBe 15_000L * 3 + 10_000L + 3_000L * 3
    }
})
