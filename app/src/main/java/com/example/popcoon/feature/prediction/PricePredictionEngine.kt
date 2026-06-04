package com.example.popcoon.feature.prediction

import com.example.popcoon.data.model.PriceRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Holt's linear smoothing による価格予測。
 * Python 実装 (popcoon_core.py::predict_price) と完全に同一ロジック。
 *
 * 予測値の整合性は Differential testing (test_differential.py) で保証。
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
        /** 予測区間の半幅 (±この値、残差標準偏差ベース)。0 = 算出不能 */
        val predictionMargin: Long = 0,
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

        // 予測区間: ワンステップ予測残差の標準偏差から算出
        // (arXiv: Holt-Winters は解釈可能な区間推定が可能。
        //  68% 区間 ≈ ±1σ を採用し UI で「±¥Y」と提示)
        val margin = predictionMargin(cleaned)

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
        )
    }

    /**
     * ワンステップ予測残差の標準偏差を予測区間の半幅とする。
     * Holt's linear で各時点を1期先予測し、実測との差の RMSE を返す。
     */
    private fun predictionMargin(data: List<Double>): Long {
        if (data.size < 3) return 0L
        var level = data[0]
        var trend = if (data.size >= 2) data[1] - data[0] else 0.0
        var sumSq = 0.0
        var count = 0
        for (i in 1 until data.size) {
            val forecast = level + trend  // 1期先予測
            val y = data[i]
            val err = y - forecast
            sumSq += err * err
            count++
            val prevLevel = level
            level = ALPHA * y + (1 - ALPHA) * (level + trend)
            trend = BETA * (level - prevLevel) + (1 - BETA) * trend
        }
        if (count == 0) return 0L
        return kotlin.math.sqrt(sumSq / count).toLong()
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
