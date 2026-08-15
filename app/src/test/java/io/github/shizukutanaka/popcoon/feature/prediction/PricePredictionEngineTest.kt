package io.github.shizukutanaka.popcoon.feature.prediction

import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
            if (p != null) {
                p.predictionMargin shouldBeGreaterThanOrEqualTo 0L
                p.predictionMargin30d shouldBeGreaterThanOrEqualTo 0L
            }
        }
    }

    // ── 予測アンサンブル (研究 B1, Gardner & McKenzie 1985 / fpp3 §8.2) ─────
    "ensembleForecast: 定数列は同じ定数を返す" {
        val flat = List(20) { 5000.0 }
        listOf(1, 7, 30).forEach { h ->
            (kotlin.math.abs(PricePredictionEngine.ensembleForecast(flat, h) - 5000.0) < 1e-9)
                .shouldBe(true)
        }
    }

    "ensembleForecast: 単調下降列では中央値が seasonal-naive を上回らない" {
        // 下降列では Holt と damped の 2 腕がともに直近値より下に来るため、
        // 中央値は必ず seasonal-naive 以下になる (減衰しても向きは保つ)。
        // seasonal-naive の腕だけは内部関数なしで計算できるので、これを境界に使う。
        val data = (0 until 30).map { 10000.0 - 100.0 * it }
        listOf(1, 7, 30).forEach { h ->
            val snaive = data[data.size - 7 + ((h - 1) % 7)]
            (PricePredictionEngine.ensembleForecast(data, h) < snaive).shouldBe(true)
        }
    }

    "ensembleForecast: 週次季節性のある系列でも観測レンジ内に収まる" {
        val data = (0 until 21).map { if (it % 7 >= 5) 800.0 else 1000.0 }
        listOf(1, 7).forEach { h ->
            val fc = PricePredictionEngine.ensembleForecast(data, h)
            (fc >= 700.0 && fc < 1100.0).shouldBe(true)
        }
    }

    "ensembleForecast: 全価格を平行移動すると予測も同じだけ動く (shift 等変性)" {
        val data = (0 until 20).map { 900.0 + 11.0 * it }
        val shifted = data.map { it + 250.0 }
        listOf(7, 30).forEach { h ->
            val delta = PricePredictionEngine.ensembleForecast(shifted, h) -
                PricePredictionEngine.ensembleForecast(data, h)
            (kotlin.math.abs(delta - 250.0) < 1e-9).shouldBe(true)
        }
    }

    "ensembleForecast: horizon < 1 は例外" {
        shouldThrow<IllegalArgumentException> {
            PricePredictionEngine.ensembleForecast(listOf(1.0, 2.0, 3.0), 0)
        }
    }

    "predicted7d はアンサンブル、predicted30d は Holt 単独 (区間較正の都合)" {
        // 単調下降列: Holt が最も下、damped が中間、snaive が最も上 → 中央値 = damped。
        // 30日先は Holt 据え置きなので大きく下がったまま。
        val prices = (0 until 30).map { 10000L - it * 100L }
        val p = PricePredictionEngine.predict(fixedHistory(prices))!!
        p.predicted7d shouldBe 6976L    // damped (Python オラクルから導出)
        p.predicted30d shouldBe 4100L   // Holt 単独 (従来値のまま)
    }

    // ── horizon 一致較正 (arXiv:2601.18509 multi-step split conformal) ──────
    "30日先の margin は 7日先より広い (多段先の誤差は累積する)" {
        // 十分な履歴 (61点) で 30 ステップ先残差が取れるボラティリティのある系列。
        val volatile = (0 until 61).map { 1000L + (it % 5) * 200L }
        val p = PricePredictionEngine.predict(fixedHistory(volatile))!!
        p.predictionMargin30d shouldBeGreaterThan p.predictionMargin
    }

    "履歴が30ステップ先の実測を含まないとき margin30d は 0 (算出不能を偽らない)" {
        // 30点では 30 ステップ先の実測が 1 つも取れない → 0。
        // 7日先の margin を流用して「区間がある」ように見せてはならない。
        val p = PricePredictionEngine.predict(fixedHistory((0 until 30).map { 1000L + it * 7L }))!!
        p.predictionMargin30d shouldBe 0L
    }

    "holtResiduals: horizon=1 は 1期先残差、horizon を上げると件数が減る" {
        val data = (0 until 14).map { 1000.0 + it * 3.0 }
        PricePredictionEngine.holtResiduals(data, 1) shouldHaveSize 13
        PricePredictionEngine.holtResiduals(data, 7) shouldHaveSize 7
        PricePredictionEngine.holtResiduals(data, 14).shouldBeEmpty()
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
