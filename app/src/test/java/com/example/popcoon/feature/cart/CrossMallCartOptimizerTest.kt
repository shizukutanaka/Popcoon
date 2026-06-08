package com.example.popcoon.feature.cart

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Python 参照 (proto_cross_mall_cart.py) との完全一致パリティテスト。
 * ゴールデン値は PORTING_SPEC.md #4（Python 実行で取得）。
 */
class CrossMallCartOptimizerTest : StringSpec({

    val stdMalls = mapOf(
        "amazon" to CrossMallCartOptimizer.MallConfig(shipping = 500.0, freeThreshold = 3000.0),
        "rakuten" to CrossMallCartOptimizer.MallConfig(shipping = 500.0, freeThreshold = 3000.0),
    )

    "空カートは total=0" {
        val r = CrossMallCartOptimizer.optimize(emptyList(), stdMalls)
        r.total shouldBe (0.0 plusOrMinus 1e-9)
        r.assignment shouldBe emptyMap()
    }

    "単品: 送料込みで安い方に割り当て" {
        val items = listOf(
            CrossMallCartOptimizer.CartItem("x", mapOf("amazon" to 1000.0, "rakuten" to 900.0)),
        )
        val r = CrossMallCartOptimizer.optimize(items, stdMalls)
        // rakuten 900+500=1400 < amazon 1000+500=1500
        r.assignment[0] shouldBe "rakuten"
        r.total shouldBe (1400.0 plusOrMinus 1e-9)
    }

    // ── PORTING_SPEC.md #4 パリティ ──────────────────────────────────────────
    "パリティ: amazon 集約で送料無料ライン到達 → total=2000" {
        val malls = mapOf(
            "amazon" to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 2000.0),
            "rakuten" to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 5000.0),
        )
        val items = listOf(
            CrossMallCartOptimizer.CartItem("a", mapOf("amazon" to 1000.0, "rakuten" to 900.0)),
            CrossMallCartOptimizer.CartItem("b", mapOf("amazon" to 1000.0, "rakuten" to 1300.0)),
        )
        val r = CrossMallCartOptimizer.optimize(items, malls)
        r.assignment shouldBe mapOf(0 to "amazon", 1 to "amazon")
        r.total shouldBe (2000.0 plusOrMinus 1e-9)
        r.shippingTotal shouldBe (0.0 plusOrMinus 1e-9)
        r.couponTotal shouldBe (0.0 plusOrMinus 1e-9)
        r.numMalls shouldBe 1
    }

    "送料ゼロ環境: 各 item 最安モールへ分割が最適" {
        val malls = mapOf(
            "amazon" to CrossMallCartOptimizer.MallConfig(),
            "rakuten" to CrossMallCartOptimizer.MallConfig(),
        )
        val items = listOf(
            CrossMallCartOptimizer.CartItem("a", mapOf("amazon" to 1000.0, "rakuten" to 1200.0)),
            CrossMallCartOptimizer.CartItem("b", mapOf("amazon" to 1500.0, "rakuten" to 1100.0)),
        )
        val r = CrossMallCartOptimizer.optimize(items, malls)
        r.assignment shouldBe mapOf(0 to "amazon", 1 to "rakuten")
        r.total shouldBe (2100.0 plusOrMinus 1e-9)
    }

    "選択肢1つの item は強制割り当て" {
        val items = listOf(CrossMallCartOptimizer.CartItem("x", mapOf("amazon" to 1000.0)))
        val r = CrossMallCartOptimizer.optimize(items, stdMalls)
        r.assignment[0] shouldBe "amazon"
        // 1000 < 3000 → 送料500
        r.total shouldBe (1500.0 plusOrMinus 1e-9)
    }

    "選択肢なしは例外" {
        shouldThrow<IllegalArgumentException> {
            CrossMallCartOptimizer.optimize(
                listOf(CrossMallCartOptimizer.CartItem("x", emptyMap())),
                stdMalls,
            )
        }
    }

    "決定的: 同じ入力に同じ出力" {
        val items = listOf(
            CrossMallCartOptimizer.CartItem("a", mapOf("amazon" to 1000.0, "rakuten" to 1000.0)),
            CrossMallCartOptimizer.CartItem("b", mapOf("amazon" to 1000.0, "rakuten" to 1000.0)),
        )
        CrossMallCartOptimizer.optimize(items, stdMalls) shouldBe
            CrossMallCartOptimizer.optimize(items, stdMalls)
    }

    "タイブレーク: 同額なら配送回数が少ない方" {
        val malls = mapOf(
            "a" to CrossMallCartOptimizer.MallConfig(),
            "b" to CrossMallCartOptimizer.MallConfig(),
        )
        val items = listOf(
            CrossMallCartOptimizer.CartItem("x", mapOf("a" to 1000.0, "b" to 1000.0)),
            CrossMallCartOptimizer.CartItem("y", mapOf("a" to 500.0, "b" to 500.0)),
        )
        val r = CrossMallCartOptimizer.optimize(items, malls)
        r.total shouldBe (1500.0 plusOrMinus 1e-9)
        r.numMalls shouldBe 1
    }

    "qty が subtotal に反映される" {
        val items = listOf(
            CrossMallCartOptimizer.CartItem("x", mapOf("amazon" to 1000.0, "rakuten" to 1200.0), qty = 2),
        )
        val r = CrossMallCartOptimizer.optimize(items, stdMalls)
        // amazon: 1000*2=2000 <3000 → 送料500 → 2500; rakuten: 2400<3000 → 500 → 2900
        r.assignment[0] shouldBe "amazon"
        r.total shouldBe (2500.0 plusOrMinus 1e-9)
    }

    "モールクーポンが最適解を変える" {
        val malls = mapOf(
            "amazon" to CrossMallCartOptimizer.MallConfig(
                coupons = listOf(CrossMallCartOptimizer.Coupon(2000.0, 300.0)),
            ),
            "rakuten" to CrossMallCartOptimizer.MallConfig(),
        )
        val items = listOf(
            CrossMallCartOptimizer.CartItem("a", mapOf("amazon" to 1100.0, "rakuten" to 1000.0)),
            CrossMallCartOptimizer.CartItem("b", mapOf("amazon" to 1100.0, "rakuten" to 1000.0)),
        )
        val r = CrossMallCartOptimizer.optimize(items, malls)
        // both amazon: 2200 - 300 = 1900 < both rakuten: 2000
        r.assignment shouldBe mapOf(0 to "amazon", 1 to "amazon")
        r.total shouldBe (1900.0 plusOrMinus 1e-9)
        r.couponTotal shouldBe (300.0 plusOrMinus 1e-9)
    }

    "大規模カート → 貪欲フォールバック" {
        // 3モール × 14 items = 3^14 > bruteCap=1000 → greedy
        val malls = mapOf(
            "a" to CrossMallCartOptimizer.MallConfig(),
            "b" to CrossMallCartOptimizer.MallConfig(),
            "c" to CrossMallCartOptimizer.MallConfig(),
        )
        val items = (0 until 14).map { i ->
            CrossMallCartOptimizer.CartItem("$i", mapOf("a" to 100.0 + i, "b" to 90.0 + i, "c" to 110.0 + i))
        }
        val r = CrossMallCartOptimizer.optimize(items, malls, bruteCap = 1000)
        r.greedy.shouldBeTrue()
        r.assignment.values.all { it == "b" } shouldBe true
    }
})
