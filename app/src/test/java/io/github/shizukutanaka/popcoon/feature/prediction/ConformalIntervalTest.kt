package io.github.shizukutanaka.popcoon.feature.prediction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqualTo
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
})
