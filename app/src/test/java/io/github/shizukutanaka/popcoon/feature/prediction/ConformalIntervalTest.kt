package io.github.shizukutanaka.popcoon.feature.prediction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * Python 参照 (proto_conformal_interval.py) との完全一致パリティテスト。
 * ゴールデン値は PORTING_SPEC.md A6（Python 実行で取得）。
 */
class ConformalIntervalTest : StringSpec({

    val residuals = listOf(
        -30.0, -20.0, -10.0, -5.0, -2.0, 0.0, 2.0, 5.0, 10.0, 20.0, 30.0,
    )

    "空残差は margin 0" {
        ConformalInterval.conformalMargin(emptyList(), 0.1) shouldBe 0.0
    }

    "パリティ: alpha 0.1 -> 30" {
        ConformalInterval.conformalMargin(residuals, 0.1) shouldBe (30.0 plusOrMinus 1e-9)
    }

    "パリティ: alpha 0.2 -> 30" {
        ConformalInterval.conformalMargin(residuals, 0.2) shouldBe (30.0 plusOrMinus 1e-9)
    }

    "パリティ: alpha 0.3 -> 20" {
        ConformalInterval.conformalMargin(residuals, 0.3) shouldBe (20.0 plusOrMinus 1e-9)
    }

    "margin は alpha に対して単調 (小さいほど広い)" {
        val strict = ConformalInterval.conformalMargin(residuals, 0.05)
        val loose = ConformalInterval.conformalMargin(residuals, 0.4)
        strict shouldBeGreaterThanOrEqualTo loose
    }

    "非空で不正な alpha は例外" {
        shouldThrow<IllegalArgumentException> {
            ConformalInterval.conformalMargin(residuals, 0.0)
        }
        shouldThrow<IllegalArgumentException> {
            ConformalInterval.conformalMargin(residuals, 1.0)
        }
    }

    "区間は point を中心に対称" {
        val iv = ConformalInterval.predictInterval(1000.0, residuals, 0.1)
        iv.low shouldBe (1000.0 - iv.margin)
        iv.high shouldBe (1000.0 + iv.margin)
        iv.margin shouldBe (30.0 plusOrMinus 1e-9)
    }

    // ── adaptiveConformalMargin (quantile tracking / Conformal PID P項) ────────

    "adaptive: 空残差は margin 0" {
        ConformalInterval.adaptiveConformalMargin(emptyList(), 0.1) shouldBe 0.0
    }

    "adaptive: 非空で不正な alpha は例外" {
        shouldThrow<IllegalArgumentException> {
            ConformalInterval.adaptiveConformalMargin(residuals, 0.0)
        }
        shouldThrow<IllegalArgumentException> {
            ConformalInterval.adaptiveConformalMargin(residuals, 1.0)
        }
    }

    "adaptive: 単一残差でもクラッシュせず静的分位点に極めて近い値" {
        val m = ConformalInterval.adaptiveConformalMargin(listOf(5.0), 0.1)
        (m > 4.999999 && m <= 5.0) shouldBe true
    }

    "adaptive: 静的分位点は順序不変だが adaptive は直近の沈静化を反映する" {
        val shift = List(20) { 2.0 } + List(10) { 50.0 }    // 直近が高ボラ化
        val shrink = List(20) { 50.0 } + List(10) { 2.0 }   // 直近が沈静化
        // 静的分位点は全体集合の分位点なので順序を無視 = 同じ値
        ConformalInterval.conformalMargin(shift, 0.1) shouldBe
            ConformalInterval.conformalMargin(shrink, 0.1)
        // adaptive はオンライン追跡で順序に反応し、shrink の方が margin が小さい
        val mShift = ConformalInterval.adaptiveConformalMargin(shift, 0.1)
        val mShrink = ConformalInterval.adaptiveConformalMargin(shrink, 0.1)
        mShrink shouldBeLessThan mShift
    }

    "adaptive: 直近1件の急変動 (ショック) に静的分位点より大きく反応する" {
        val shock = List(29) { 1.0 } + listOf(1000.0)
        val staticM = ConformalInterval.conformalMargin(shock, 0.1)
        val adaptiveM = ConformalInterval.adaptiveConformalMargin(shock, 0.1)
        staticM shouldBeLessThan 5.0
        adaptiveM shouldBeGreaterThanOrEqualTo staticM * 10
    }

    "adaptive: margin は常に非負 (極端な eta でもクランプされる)" {
        val m = ConformalInterval.adaptiveConformalMargin(
            List(10) { 0.0 }, 0.1, eta = 100.0,
        )
        m shouldBeGreaterThanOrEqualTo 0.0
    }

    "adaptive: 決定的" {
        ConformalInterval.adaptiveConformalMargin(residuals, 0.1) shouldBe
            ConformalInterval.adaptiveConformalMargin(residuals, 0.1)
    }
})
