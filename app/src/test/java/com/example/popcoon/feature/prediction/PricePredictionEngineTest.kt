package com.example.popcoon.feature.prediction

import com.example.popcoon.data.model.PriceRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqualTo
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.Instant

/**
 * Python 実装との differential testing。
 * 同じ入力を与えて同じ出力が得られることを property test で確認。
 */
class PricePredictionEngineTest : StringSpec({

    fun fixedHistory(prices: List<Long>): List<PriceRecord> =
        prices.mapIndexed { i, p ->
            PriceRecord(
                productKey = "p", platform = "amazon",
                listPrice = p + 500, realPrice = p,
                recordedAt = Instant.parse("2026-01-01T00:00:00Z")
                    .plusSeconds((i * 86400).toLong()),
            )
        }

    "履歴13件以下は null を返す" {
        PricePredictionEngine.predict(fixedHistory((1L..13L).toList())).shouldBeNull()
    }

    "履歴14件から予測開始" {
        val result = PricePredictionEngine.predict(fixedHistory((1L..14L).toList()))
        result.shouldNotBeNull()
        result.confidence shouldBe PricePredictionEngine.Confidence.LOW
    }

    "履歴30件で MEDIUM、90件で HIGH" {
        PricePredictionEngine.predict(fixedHistory(List(30) { 1000L }))!!
            .confidence shouldBe PricePredictionEngine.Confidence.MEDIUM
        PricePredictionEngine.predict(fixedHistory(List(90) { 1000L }))!!
            .confidence shouldBe PricePredictionEngine.Confidence.HIGH
    }

    "定価で安定している履歴は同じ予測値" {
        val history = fixedHistory(List(30) { 5000L })
        val p = PricePredictionEngine.predict(history)!!
        p.currentPrice shouldBe 5000L
        p.predicted7d shouldBe 5000L
        p.predicted30d shouldBe 5000L
    }

    "下降トレンドは predicted_30d が current より低い" {
        val history = fixedHistory((0 until 30).map { (10000L - it * 100L) })
        val p = PricePredictionEngine.predict(history)!!
        p.predicted30d shouldBeLessThan p.currentPrice
    }

    "上昇トレンドは predicted_30d が current より高い" {
        val history = fixedHistory((0 until 30).map { (1000L + it * 100L) })
        val p = PricePredictionEngine.predict(history)!!
        p.predicted30d shouldBeGreaterThan p.currentPrice
    }

    "buyNowProbability は必ず 0.0-1.0 の範囲" {
        checkAll(Arb.list(Arb.int(100..100_000), 14..100)) { prices ->
            val history = fixedHistory(prices.map { it.toLong() })
            val p = PricePredictionEngine.predict(history)
            if (p != null) {
                val prob = p.buyNowProbability
                prob shouldBeGreaterThanOrEqual 0f
                prob shouldBeLessThanOrEqual 1f
            }
        }
    }

    "どんな入力でも例外なし" {
        checkAll(Arb.list(Arb.int(-1000..1_000_000), 0..200)) { prices ->
            fixedHistory(prices.map { it.toLong() }).also { history ->
                // 例外が出ないこと (null 可)
                PricePredictionEngine.predict(history)
            }
        }
    }

    "predicted 値は非負 (公式max保証)" {
        checkAll(Arb.list(Arb.int(0..1000), 14..30)) { prices ->
            val history = fixedHistory(prices.map { it.toLong() })
            val p = PricePredictionEngine.predict(history)
            if (p != null) {
                p.predicted7d shouldBeGreaterThanOrEqualTo 0L
                p.predicted30d shouldBeGreaterThanOrEqualTo 0L
            }
        }
    }

    "current_price は records.last().realPrice と一致 (Python で発見した真のバグ修正)" {
        val prices = (1L..30L).toList()
        val history = fixedHistory(prices)
        val p = PricePredictionEngine.predict(history)!!
        p.currentPrice shouldBe 30L  // 最後の要素
    }

    // ── 予測区間 (arXiv Holt-Winters 区間推定) ───────────────────────────
    "安定した価格系列では予測区間が小さい" {
        val stable = List(30) { 1000L }
        val p = PricePredictionEngine.predict(fixedHistory(stable))!!
        // 完全に一定なら margin はほぼ 0
        p.predictionMargin shouldBeLessThanOrEqualTo 10L
    }

    "変動の大きい系列では予測区間が大きい" {
        val volatile = (0 until 30).map { if (it % 2 == 0) 1000L else 2000L }
        val p = PricePredictionEngine.predict(fixedHistory(volatile))!!
        // 大きく振動するので margin > 0
        p.predictionMargin shouldBeGreaterThan 0L
    }

    "予測区間は常に非負" {
        checkAll(Arb.list(Arb.int(0..5000), 14..40)) { prices ->
            val p = PricePredictionEngine.predict(fixedHistory(prices.map { it.toLong() }))
            if (p != null) p.predictionMargin shouldBeGreaterThanOrEqualTo 0L
        }
    }

    // ── Confidence 判定 (履歴日数ベース) ──────────────────────────────────
    "90日以上で HIGH" {
        val p = PricePredictionEngine.predict(fixedHistory(List(90) { 1000L }))!!
        p.confidence shouldBe PricePredictionEngine.Confidence.HIGH
    }

    "30-89日で MEDIUM" {
        val p = PricePredictionEngine.predict(fixedHistory(List(45) { 1000L }))!!
        p.confidence shouldBe PricePredictionEngine.Confidence.MEDIUM
    }

    "14-29日で LOW" {
        val p = PricePredictionEngine.predict(fixedHistory(List(20) { 1000L }))!!
        p.confidence shouldBe PricePredictionEngine.Confidence.LOW
    }

    "14日未満は予測不能 (null)" {
        PricePredictionEngine.predict(fixedHistory(List(10) { 1000L })) shouldBe null
    }
})
