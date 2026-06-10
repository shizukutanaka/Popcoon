package com.example.popcoon.feature.tco

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
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
        // レーザーはインクジェットより消耗品が高い (トナー6000円/本 × 1.5回/年)
        val inkjet = TCOCalculator.calculate(25_000, "inkjet_printer", 5)
        (r.consumablesTotal > inkjet.consumablesTotal) shouldBe true
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
        (fridge.energyTotal > laptop.energyTotal) shouldBe true
    }

    "エアコン: 高消費電力" {
        val r = TCOCalculator.calculate(80_000, "air_conditioner", 5)
        r.energyTotal shouldBeGreaterThan 0L
        // 700W × 8h → 最も電気代が高いはず
        val laptop = TCOCalculator.calculate(80_000, "laptop", 5)
        (r.energyTotal > laptop.energyTotal) shouldBe true
    }

    "コーヒーカプセル: 365日 × 80円 × intensity" {
        val r = TCOCalculator.calculate(20_000, "coffee_capsule", 1)
        // 80 × 365 × 1.0 = 29,200
        r.consumablesTotal shouldBe 29_200L
    }

    "未知カテゴリ: 消耗品・電気代 0、購入価格のみ" {
        val r = TCOCalculator.calculate(50_000, "unknown_device", 5)
        r.consumablesTotal shouldBe 0L
        r.energyTotal shouldBe 0L
        r.totalTco shouldBe 50_000L
    }

    "intensity 2.0: 消耗品が倍増" {
        val normal = TCOCalculator.calculate(15_000, "inkjet_printer", 5, intensity = 1.0)
        val heavy = TCOCalculator.calculate(15_000, "inkjet_printer", 5, intensity = 2.0)
        (heavy.consumablesTotal > normal.consumablesTotal) shouldBe true
    }

    "レーザープリンター intensity 2.0: ドラムも intensity に比例 (drum bug regression)" {
        val normal = TCOCalculator.calculate(25_000, "laser_printer", 1, intensity = 1.0)
        val heavy = TCOCalculator.calculate(25_000, "laser_printer", 1, intensity = 2.0)
        // 全消耗品 (toner/drum/paper) が intensity に比例するので 2倍になる
        heavy.consumablesTotal shouldBe normal.consumablesTotal * 2
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
            (r.totalTco >= 0L) shouldBe true
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

    "inferCategory はどんな入力でも例外なし" {
        checkAll(Arb.string(0..100)) { s ->
            TCOCalculator.inferCategory(s)
        }
    }
})
