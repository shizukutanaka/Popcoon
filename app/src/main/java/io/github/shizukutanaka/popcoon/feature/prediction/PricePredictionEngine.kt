package io.github.shizukutanaka.popcoon.feature.prediction

import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Holt's linear smoothing による価格予測。
 * Python 実装 (popcoon_core.py::predict_price) と完全に同一ロジック。
 *
 * 予測値の整合性は Differential testing (test_differential.py) で保証。
 *
 * 後続強化（PORTING_SPEC.md 配線）:
 *   - predictionMargin: RMSE → Conformal 区間（分布自由な被覆保証、A6）
 *   - seasonalForecast7d: DLinear 風季節分解予測を並走（A1）
 */
object PricePredictionEngine {

    enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }

    data class Prediction(
        val currentPrice: Long,
        val predicted7d: Long,
        val predicted30d: Long,
        val buyNowProbability: Float,
        val historicLow: Long,
        val historicHigh: Long,
        val confidence: Confidence,
        /** 予測区間の半幅（±この値）。Conformal margin (90% 被覆保証)。0 = 算出不能。 */
        val predictionMargin: Long = 0,
        /** 季節分解（DLinear 風）による 7 日後予測。0 = 算出不能。 */
        val seasonalForecast7d: Long = 0,
    )

    private const val MIN_RECORDS = 14
    private const val ALPHA = 0.3
    private const val BETA = 0.1

    fun predict(records: List<PriceRecord>): Prediction? {
        if (records.size < MIN_RECORDS) return null

        val data = records.map { it.realPrice.toDouble() }
        val cleaned = removeOutliersIqr(data)
        if (cleaned.size < 2) return null

        val (level, trend) = holtLinear(cleaned)
        val pred7 = max(0L, (level + trend * 7).toLong())
        val pred30 = max(0L, (level + trend * 30).toLong())

        // A6: Conformal 予測区間（PORTING_SPEC.md A6, arXiv:2505.08158）。
        // 静的 split-conformal ではなく適応 (quantile tracking, Conformal PID の P項,
        // arXiv:2307.16895) を使う — 2026-07 リサーチで確認: セール期のボラティリティ
        // 急変等の分布シフトに対し、順序不変な静的分位点より直近の実績を反映できる。
        val residuals = holtResiduals(cleaned)
        val margin = if (residuals.isNotEmpty())
            ConformalInterval.adaptiveConformalMargin(residuals).toLong()
        else 0L

        // A1: 季節分解予測（PORTING_SPEC.md A1, arXiv:2403.14587）
        val seasonalSeries = SeasonalDecompForecast.forecast(cleaned, 7, 7)
        val seasonalF7d = if (seasonalSeries.size == 7) max(0L, seasonalSeries[6].toLong()) else 0L

        val current = records.last().realPrice  // ← Python port の真のバグ修正後
        val low = cleaned.min().toLong()
        val high = cleaned.max().toLong()

        // 買い時確率: percentile + trend
        val percentile = cleaned.count { it >= current }.toDouble() / cleaned.size
        val trendBoost = if (trend < 0) 0.3 else 0.0
        val buyProb = (percentile * 0.5 + trendBoost).toFloat().coerceIn(0f, 1f)

        val confidence = when {
            records.size >= 90 -> Confidence.HIGH
            records.size >= 30 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return Prediction(
            currentPrice = current,
            predicted7d = pred7,
            predicted30d = pred30,
            buyNowProbability = buyProb,
            historicLow = low,
            historicHigh = high,
            confidence = confidence,
            predictionMargin = margin,
            seasonalForecast7d = seasonalF7d,
        )
    }

    /**
     * Holt 1期先予測の残差列。ConformalInterval への入力に使う。
     */
    private fun holtResiduals(data: List<Double>): List<Double> {
        if (data.size < 3) return emptyList()
        var level = data[0]
        var trend = if (data.size >= 2) data[1] - data[0] else 0.0
        val result = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val forecast = level + trend
            val y = data[i]
            result += y - forecast
            val prevLevel = level
            level = ALPHA * y + (1 - ALPHA) * (level + trend)
            trend = BETA * (level - prevLevel) + (1 - BETA) * trend
        }
        return result
    }

    private fun holtLinear(data: List<Double>): Pair<Double, Double> {
        var level = data[0]
        var trend = if (data.size >= 2) data[1] - data[0] else 0.0
        for (i in 1 until data.size) {
            val y = data[i]
            val prevLevel = level
            level = ALPHA * y + (1 - ALPHA) * (level + trend)
            trend = BETA * (level - prevLevel) + (1 - BETA) * trend
        }
        return level to trend
    }

    private fun removeOutliersIqr(data: List<Double>): List<Double> {
        if (data.size < 4) return data
        val sorted = data.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[sorted.size * 3 / 4]
        val iqr = q3 - q1
        val lower = q1 - 1.5 * iqr
        val upper = q3 + 1.5 * iqr
        return data.filter { it in lower..upper }
    }
}
