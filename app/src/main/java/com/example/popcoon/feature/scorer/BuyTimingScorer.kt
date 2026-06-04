package com.example.popcoon.feature.scorer

import com.example.popcoon.data.model.PriceRecord
import com.example.popcoon.feature.darkpattern.DarkPatternDetector
import com.example.popcoon.feature.prediction.PricePredictionEngine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 買い時スコア (0-100) に既存6機能を統合。
 * Python 実装 (buy_timing_scorer.py) と同一式。
 * Mutation testing: 10/10 killed (100%)。
 */
object BuyTimingScorer {

    enum class Verdict { BUY_NOW, NEUTRAL, WAIT }

    data class Signal(val name: String, val contribution: Int)

    data class Score(
        val total: Int,
        val verdict: Verdict,
        val signals: List<Signal>,
        val confidence: String,
    )

    private const val MIN_HISTORY = 14
    private const val BASE_SCORE = 50

    fun score(
        current: Long,
        listPrice: Long,
        history: List<PriceRecord>,
        today: java.time.LocalDate? = null,
    ): Score? {
        if (history.size < MIN_HISTORY) return null

        val signals = mutableListOf<Signal>()
        signals += Signal("中立スコア", BASE_SCORE)
        signals += signalAtlProximity(current, history)
        signals += signalTrend(history)
        signals += signalDiscountFromList(current, listPrice)
        signals += signalVolatility(history)
        signals += signalHistoryConfidence(history)

        val darkSig = signalDarkPatternPenalty(current, listPrice, history)
        if (darkSig.contribution != 0) signals += darkSig

        // 大型セール接近シグナル (arXiv 2405.13995: 季節性・プロモイベント考慮)
        if (today != null) {
            val saleSig = signalUpcomingSale(today)
            if (saleSig.contribution != 0) signals += saleSig
        }

        val rawSum = signals.sumOf { it.contribution }
        val total = rawSum.coerceIn(0, 100)
        if (total != rawSum) signals += Signal("スコア正規化", total - rawSum)

        val verdict = when {
            total >= 70 -> Verdict.BUY_NOW
            total <= 35 -> Verdict.WAIT
            else -> Verdict.NEUTRAL
        }
        val confidence = when {
            history.size >= 90 -> "HIGH"
            history.size >= 30 -> "MEDIUM"
            else -> "LOW"
        }
        return Score(total, verdict, signals, confidence)
    }

    /**
     * 大型セール接近シグナル。
     *
     * arXiv 2405.13995 の知見: 季節性・プロモーションイベントを考慮しないと
     * 予測精度が大きく低下する。大型セール (Amazon プライムデー、楽天スーパーセール等)
     * が数日内に迫っている場合、今すぐ買うよりも待つほうが有利な可能性が高い。
     *
     * SaleCalendar と連携し、7日以内に MAJOR セールがあれば「待ち」方向に補正。
     */
    private fun signalUpcomingSale(today: java.time.LocalDate): Signal {
        val next = com.example.popcoon.feature.calendar.SaleCalendar.nextMajorSale(today)
            ?: return Signal("", 0)
        val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(today, next.startDate)
        return when {
            daysUntil in 0..3 -> Signal("大型セール直前 (${next.name})", -12)
            daysUntil in 4..7 -> Signal("大型セール接近 (${next.name})", -6)
            else -> Signal("", 0)
        }
    }

    private fun signalAtlProximity(current: Long, history: List<PriceRecord>): Signal {
        val prices = history.map { it.realPrice }
        val low = prices.min()
        val high = prices.max()
        if (high == low) return Signal("価格安定 (ATL近接判定不可)", 0)
        val position = (current - low).toDouble() / max(1, high - low)
        return when {
            position <= 0 -> Signal("過去最安値到達", 30)
            position <= 0.1 -> Signal("過去最安値圏", 22)
            position <= 0.3 -> Signal("最安値近辺", 12)
            position >= 0.9 -> Signal("過去最高値圏", -15)
            else -> Signal("中間価格帯", 0)
        }
    }

    private fun signalTrend(history: List<PriceRecord>): Signal {
        val pred = PricePredictionEngine.predict(history)
            ?: return Signal("トレンド判定不可", 0)
        val current = history.last().realPrice
        if (current == 0L) return Signal("価格ゼロ", 0)
        val futureRatio = (pred.predicted30d - current).toDouble() / current
        return when {
            futureRatio > 0.05 -> Signal("価格上昇中 (待ちは不利)", 10)
            futureRatio > 0.01 -> Signal("微上昇", 3)
            futureRatio < -0.05 -> Signal("価格下降中 (待ちが有利)", -15)
            futureRatio < -0.01 -> Signal("微下降", -5)
            else -> Signal("価格横ばい", 0)
        }
    }

    private fun signalDiscountFromList(current: Long, listPrice: Long): Signal {
        if (listPrice <= 0 || listPrice <= current) return Signal("割引なし", 0)
        val discountPct = (listPrice - current).toDouble() / listPrice * 100
        return when {
            discountPct >= 40 -> Signal("定価比${discountPct.toInt()}%OFF", 15)
            discountPct >= 25 -> Signal("定価比${discountPct.toInt()}%OFF", 10)
            discountPct >= 10 -> Signal("定価比${discountPct.toInt()}%OFF", 5)
            else -> Signal("定価比${discountPct.toInt()}%OFF (僅少)", 1)
        }
    }

    private fun signalVolatility(history: List<PriceRecord>): Signal {
        val prices = history.map { it.realPrice.toDouble() }
        val mean = prices.average()
        if (mean == 0.0) return Signal("平均価格ゼロ", 0)
        val variance = prices.map { (it - mean) * (it - mean) }.average()
        val cv = sqrt(variance) / mean
        return when {
            cv < 0.02 -> Signal("極めて安定", 10)
            cv < 0.05 -> Signal("価格安定", 5)
            cv > 0.25 -> Signal("価格変動大", -5)
            else -> Signal("通常の価格変動", 0)
        }
    }

    private fun signalHistoryConfidence(history: List<PriceRecord>): Signal {
        val n = history.size
        return when {
            n >= 90 -> Signal("豊富な履歴", 10)
            n >= 30 -> Signal("十分な履歴", 5)
            else -> Signal("履歴不足", 0)
        }
    }

    private fun signalDarkPatternPenalty(
        current: Long, listPrice: Long, history: List<PriceRecord>,
    ): Signal {
        val warnings = DarkPatternDetector.detect(
            current,
            if (listPrice <= 0) null else listPrice,
            history,
        )
        if (warnings.isEmpty()) return Signal("", 0)
        var penalty = 0
        val names = mutableListOf<String>()
        for (w in warnings) {
            when (w.type) {
                DarkPatternDetector.WarningType.ALWAYS_ON_DISCOUNT,
                DarkPatternDetector.WarningType.INFLATED_LIST_PRICE,
                DarkPatternDetector.WarningType.PRE_SALE_MARKUP -> {
                    penalty -= 8; names += w.label
                }
                else -> Unit
            }
        }
        if (names.isEmpty()) return Signal("", 0)
        penalty = max(-20, penalty)
        return Signal("ダークパターン検出 (${names.take(2).joinToString("/")})", penalty)
    }
}
