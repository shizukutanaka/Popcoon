package io.github.shizukutanaka.popcoon.feature.darkpattern

import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldNotExist
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.Instant

class DarkPatternDetectorTest : StringSpec({

    fun history(prices: List<Long>, listPrice: Long = 5000): List<PriceRecord> =
        prices.mapIndexed { i, p ->
            PriceRecord(
                productKey = "p", platform = "amazon",
                listPrice = listPrice, realPrice = p,
                recordedAt = Instant.now().minusSeconds((prices.size - i).toLong() * 86400),
            )
        }

    // ── ALWAYS_ON_DISCOUNT ──────────────────────────────────────────────
    "30日間90%以上が定価未満 → 常設セール検出" {
        val h = history(List(30) { 3000L }, listPrice = 5000L)
        val w = DarkPatternDetector.detect(3000L, 5000L, h)
        w.shouldExist { it.type == DarkPatternDetector.WarningType.ALWAYS_ON_DISCOUNT }
    }

    "85%が定価未満 → 常設セール非検出 (閾値90%)" {
        val prices = List(26) { 3000L } + List(4) { 5000L }  // 26/30 = 86.7%
        val h = history(prices, listPrice = 5000L)
        val w = DarkPatternDetector.detect(3000L, 5000L, h)
        w.shouldNotExist { it.type == DarkPatternDetector.WarningType.ALWAYS_ON_DISCOUNT }
    }

    "ちょうど90%が定価未満 → 常設セール非検出 (境界値: > 0.90 なので90%は対象外 / Python oracle 一致)" {
        val prices = List(27) { 3000L } + List(3) { 5000L }  // 27/30 = 0.90 ぴったり
        val h = history(prices, listPrice = 5000L)
        val w = DarkPatternDetector.detect(3000L, 5000L, h)
        w.shouldNotExist { it.type == DarkPatternDetector.WarningType.ALWAYS_ON_DISCOUNT }
    }

    "91%超が定価未満 → 常設セール検出 (境界値超え)" {
        val prices = List(28) { 3000L } + List(2) { 5000L }  // 28/30 = 93.3%
        val h = history(prices, listPrice = 5000L)
        val w = DarkPatternDetector.detect(3000L, 5000L, h)
        w.shouldExist { it.type == DarkPatternDetector.WarningType.ALWAYS_ON_DISCOUNT }
    }

    "履歴29件以下 → 常設セール判定しない" {
        val h = history(List(29) { 3000L }, listPrice = 5000L)
        val w = DarkPatternDetector.detect(3000L, 5000L, h)
        w.shouldNotExist { it.type == DarkPatternDetector.WarningType.ALWAYS_ON_DISCOUNT }
    }

    // ── INFLATED_LIST_PRICE ─────────────────────────────────────────────
    "参考価格が実績最高値の1.5倍超 → 参考価格詐欺検出" {
        val h = history(List(30) { 3000L }, listPrice = 3000L)
        val w = DarkPatternDetector.detect(3000L, 9100L, h)  // 9100 > 3000*1.5=4500
        w.shouldExist { it.type == DarkPatternDetector.WarningType.INFLATED_LIST_PRICE }
    }

    "参考価格が実績最高値の1.5倍以下 → 非検出" {
        val h = history(List(30) { 3000L }, listPrice = 3000L)
        val w = DarkPatternDetector.detect(3000L, 4500L, h)  // 4500 = 3000*1.5 ちょうど
        w.shouldNotExist { it.type == DarkPatternDetector.WarningType.INFLATED_LIST_PRICE }
    }

    // ── PRE_SALE_MARKUP ─────────────────────────────────────────────────
    "直近7日が前7日より10%超高い + 現在セール中 → セール前値上げ検出" {
        val oldPrices = List(7) { 2000L }    // 前7日: 2000円
        val newPrices = List(7) { 2300L }    // 直近7日: 2300円 (15% 増)
        val h = history(oldPrices + newPrices, listPrice = 4000L)
        // 現在価格 < listPrice = セール中
        val w = DarkPatternDetector.detect(2300L, 4000L, h)
        w.shouldExist { it.type == DarkPatternDetector.WarningType.PRE_SALE_MARKUP }
    }

    "セール中でない → セール前値上げ非検出" {
        val h = history(List(7) { 2000L } + List(7) { 2300L }, listPrice = 2000L)
        // 現在価格 >= listPrice → セール中でない
        val w = DarkPatternDetector.detect(2300L, 2000L, h)
        w.shouldNotExist { it.type == DarkPatternDetector.WarningType.PRE_SALE_MARKUP }
    }

    // ── CHARM_PRICING ───────────────────────────────────────────────────
    "980円 → 端数価格検出" {
        val w = DarkPatternDetector.detect(980L, null, emptyList())
        w.shouldExist { it.type == DarkPatternDetector.WarningType.CHARM_PRICING }
    }

    "1980円 → 端数価格検出" {
        val w = DarkPatternDetector.detect(1980L, null, emptyList())
        w.shouldExist { it.type == DarkPatternDetector.WarningType.CHARM_PRICING }
    }

    "1000円 (下二桁 00) → 非検出" {
        val w = DarkPatternDetector.detect(1000L, null, emptyList())
        w.shouldNotExist { it.type == DarkPatternDetector.WarningType.CHARM_PRICING }
    }

    "1500円 (下二桁 00以外だが80-99の範囲外) → 非検出" {
        val w = DarkPatternDetector.detect(1500L, null, emptyList())
        w.shouldNotExist { it.type == DarkPatternDetector.WarningType.CHARM_PRICING }
    }

    // ── 複合 ───────────────────────────────────────────────────────────
    "listPrice null → null pointer なし" {
        val h = history(List(30) { 3000L })
        val w = DarkPatternDetector.detect(2980L, null, h)
        // listPrice null なので ALWAYS_ON_DISCOUNT / INFLATED 非検出、CHARM だけ
        w.shouldExist { it.type == DarkPatternDetector.WarningType.CHARM_PRICING }
    }

    "履歴空 + listPrice null → 空リスト" {
        val w = DarkPatternDetector.detect(1000L, null, emptyList())
        w.shouldBeEmpty()
    }

    // ── Property test ───────────────────────────────────────────────────
    "どんな入力でも例外なし" {
        checkAll(Arb.long(0L..1_000_000L), Arb.long(0L..2_000_000L)) { price, list ->
            DarkPatternDetector.detect(price, list.takeIf { it > 0 }, emptyList())
        }
    }

    // ── テキストベース検出 (arXiv 2411.07441 fake-scarcity/urgency) ──────
    "「残り3点」を偽希少性として検出" {
        val w = DarkPatternDetector.detectInText("人気商品 残り3点 お早めに")
        w.shouldExist { it.type == DarkPatternDetector.WarningType.FAKE_SCARCITY }
    }

    "「在庫わずか」を偽希少性として検出" {
        val w = DarkPatternDetector.detectInText("限定モデル 在庫わずか")
        w.shouldExist { it.type == DarkPatternDetector.WarningType.FAKE_SCARCITY }
    }

    "「本日限り」を偽緊急性として検出" {
        val w = DarkPatternDetector.detectInText("本日限り 特別価格")
        w.shouldExist { it.type == DarkPatternDetector.WarningType.COUNTDOWN_MANIPULATION }
    }

    "「タイムセール終了まで5分」を偽緊急性として検出" {
        val w = DarkPatternDetector.detectInText("タイムセール 終了まで5分")
        w.shouldExist { it.type == DarkPatternDetector.WarningType.COUNTDOWN_MANIPULATION }
    }

    "通常の商品名は誤検出しない" {
        val w = DarkPatternDetector.detectInText("ソニー ワイヤレスヘッドホン WH-1000XM5 ブラック")
        w.shouldBeEmpty()
    }

    "空文字は空リスト" {
        DarkPatternDetector.detectInText("").shouldBeEmpty()
    }

    "希少性 + 緊急性の両方で2件検出" {
        val w = DarkPatternDetector.detectInText("本日限り 残り2個")
        w.size shouldBe 2
    }

    "テキスト検出はどんな入力でも例外なし" {
        checkAll(Arb.string(0..200)) { s ->
            DarkPatternDetector.detectInText(s)
        }
    }

    // ── Drip Pricing 検出 (隠れコスト) ────────────────────────────────────
    "送料が本体の30%超で HIGH 警告" {
        // 本体1000円 + 送料400円 = 実質1400円 (+40%)
        val w = DarkPatternDetector.detectDripPricing(basePrice = 1000, totalPrice = 1400)
        w?.type shouldBe DarkPatternDetector.WarningType.DRIP_PRICING
        w?.severity shouldBe DarkPatternDetector.Severity.HIGH
    }

    "送料が本体の15-30%で MEDIUM 警告" {
        // 本体1000円 → 実質1200円 (+20%)
        val w = DarkPatternDetector.detectDripPricing(basePrice = 1000, totalPrice = 1200)
        w?.severity shouldBe DarkPatternDetector.Severity.MEDIUM
    }

    "送料が本体の15%未満なら警告なし" {
        // 本体1000円 → 実質1100円 (+10%)
        DarkPatternDetector.detectDripPricing(basePrice = 1000, totalPrice = 1100).shouldBeNull()
    }

    "実質価格が本体以下なら警告なし (ポイント還元等)" {
        DarkPatternDetector.detectDripPricing(basePrice = 1000, totalPrice = 950).shouldBeNull()
    }

    "本体価格ゼロは警告なし" {
        DarkPatternDetector.detectDripPricing(basePrice = 0, totalPrice = 500).shouldBeNull()
    }

    "Drip Pricing はどんな入力でも例外なし" {
        checkAll(Arb.long(0L..1_000_000L), Arb.long(0L..2_000_000L)) { base, total ->
            DarkPatternDetector.detectDripPricing(base, total)
        }
    }
})
