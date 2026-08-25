package io.github.shizukutanaka.popcoon.feature.scorer

import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternDetector
import io.github.shizukutanaka.popcoon.feature.prediction.PricePredictionEngine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 買い時スコア (0-100) に既存6機能を統合。
 * Python 実装 (buy_timing_scorer.py) と同一式。
 * Mutation testing: 10/10 killed (100%)。
 *
 * A5 配線（PORTING_SPEC.md A5）: today を渡すと曜日季節性シグナルを加算。
 *
 * このファイルは popcoon-tdd/kotlin_parity/run.sh が Android SDK 無しで直接コンパイルする
 * 対象のため Android リソース (R) への依存を持ち込まない。スコア内訳の表示文言
 * (Signal.name) はロケール非対応の日本語固定文字列のまま保持しつつ (BuyTimingScorerTest.kt /
 * test_buy_timing_scorer.py が `.name` の内容へ厳密に依存しているため変更不可)、
 * UI 表示用に `kind` という安定した enum 識別子を追加した — ロケール対応の文字列
 * リソースへのマッピングは ui/BuyScoreSignalLabels.kt (Android 依存があってよい UI 層)
 * が担当する (商用リリース監査で発見: スコア内訳が EN/KO/ZH ロケールに日本語のまま漏れていた)。
 *
 * SALE_IMMINENT/SALE_APPROACHING (具体的なセール名) と DARK_PATTERN_DETECTED
 * (具体的な検出パターン名) は、埋め込まれる子要素 (SaleCalendar.Event.name /
 * DarkPatternDetector.Warning.label) 自体もロケール解決が必要で、この pure function
 * からは (Context/Composable が無いため) 解決できない。かつ、その詳細は既に
 * SaleBanner / 警告バッジ等の専用 UI で別途ローカライズ済み表示されているため、
 * スコア内訳ではあえて具体名を省略した簡略文言にする (表示の重複を避ける実利もある)。
 */
object BuyTimingScorer {

    enum class Verdict { BUY_NOW, NEUTRAL, WAIT }

    /** UI 層 (ui/BuyScoreSignalLabels.kt) が文字列リソースへマッピングする際の安定識別子。 */
    enum class SignalKind {
        NONE, // Signal("", 0) センチネル (表示されない、当ファイル外に漏れない)
        NEUTRAL_BASE, SCORE_NORMALIZED,
        SALE_IMMINENT, SALE_APPROACHING,
        DOW_CHEAP, DOW_EXPENSIVE,
        ATL_STABLE_UNKNOWN, ATL_REACHED, ATL_NEAR, PRICE_LOW_RANGE, PRICE_HIGH_RANGE, PRICE_MID_RANGE,
        TREND_UNKNOWN, PRICE_ZERO, TREND_UP, TREND_UP_SLIGHT, TREND_DOWN, TREND_DOWN_SLIGHT, TREND_FLAT,
        NO_DISCOUNT, DISCOUNT_PCT, DISCOUNT_PCT_MINOR,
        AVG_PRICE_ZERO, VOLATILITY_VERY_STABLE, VOLATILITY_STABLE, VOLATILITY_HIGH, VOLATILITY_NORMAL,
        HISTORY_ABUNDANT, HISTORY_SUFFICIENT, HISTORY_INSUFFICIENT,
        DARK_PATTERN_DETECTED,
    }

    data class Signal(
        val name: String,
        val contribution: Int,
        val kind: SignalKind = SignalKind.NONE,
        val kindArgs: List<Any> = emptyList(),
    )

    data class Score(
        val total: Int,
        val verdict: Verdict,
        val signals: List<Signal>,
        val confidence: String,
    )

    private const val MIN_HISTORY = 14
    private const val BASE_SCORE = 50
    private val TOKYO = java.time.ZoneId.of("Asia/Tokyo")

    fun score(
        current: Long,
        listPrice: Long,
        history: List<PriceRecord>,
        today: java.time.LocalDate? = null,
    ): Score? {
        if (history.size < MIN_HISTORY) return null

        val signals = mutableListOf<Signal>()
        signals += Signal("中立スコア", BASE_SCORE, SignalKind.NEUTRAL_BASE)
        signals += signalAtlProximity(current, history)
        signals += signalTrend(history)
        signals += signalDiscountFromList(current, listPrice)
        signals += signalVolatility(history)
        signals += signalHistoryConfidence(history)

        val darkSig = signalDarkPatternPenalty(current, listPrice, history)
        if (darkSig.contribution != 0) signals += darkSig

        // 大型セール接近シグナル (arXiv 2405.13995: 季節性・プロモイベント考慮)
        // A5: 曜日季節性シグナル (PORTING_SPEC.md A5, arXiv:2105.08313)
        if (today != null) {
            val saleSig = signalUpcomingSale(today)
            if (saleSig.contribution != 0) signals += saleSig

            val dowSig = signalSeasonalDow(history, today)
            if (dowSig.contribution != 0) signals += dowSig
        }

        val rawSum = signals.sumOf { it.contribution }
        val total = rawSum.coerceIn(0, 100)
        if (total != rawSum) signals += Signal("スコア正規化", total - rawSum, SignalKind.SCORE_NORMALIZED)

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
        val next = io.github.shizukutanaka.popcoon.feature.calendar.SaleCalendar.nextMajorSale(today)
            ?: return Signal("", 0)
        val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(today, next.startDate)
        return when {
            daysUntil in 0..3 -> Signal("大型セール直前 (${next.name})", -12, SignalKind.SALE_IMMINENT)
            daysUntil in 4..7 -> Signal("大型セール接近 (${next.name})", -6, SignalKind.SALE_APPROACHING)
            else -> Signal("", 0)
        }
    }

    /**
     * 曜日季節性シグナル（A5 / PORTING_SPEC.md A5）。
     * 価格履歴から今日の曜日が統計的に安い/高い日かを学習し ±10 で返す。
     */
    private fun signalSeasonalDow(history: List<PriceRecord>, today: java.time.LocalDate): Signal {
        val dowHistory = history.map { r ->
            r.recordedAt.atZone(TOKYO).dayOfWeek.ordinal to r.realPrice.toDouble()
        }
        val s = SeasonalDowSignal.signal(dowHistory, today.dayOfWeek.ordinal)
        return if (s > 0) Signal("曜日買い時 (安い曜日)", s, SignalKind.DOW_CHEAP)
               else if (s < 0) Signal("曜日割高 (高い曜日)", s, SignalKind.DOW_EXPENSIVE)
               else Signal("", 0)
    }

    private fun signalAtlProximity(current: Long, history: List<PriceRecord>): Signal {
        // **0 以下の価格は「無料商品」ではなく取得失敗の痕跡** として除外する (2026-08)。
        // FallbackScraper は price が取れないとき realPrice=0 の Product を捏造しており
        // (cdf61dc で修正)、backend も `real_price >= 0` を許容していたため、既存の価格履歴に
        // ¥0 レコードが残っている可能性がある。1 件混入しただけで low=0 になり
        // position が常に 1 に近づくため **「過去最安値到達」が検出されなくなる**
        // (実測: 正常なら 95/BUY_NOW のケースが 40/NEUTRAL に反転)。読み出し側でも防御する。
        val prices = history.map { it.realPrice }.filter { it > 0 }
        if (prices.isEmpty()) {
            return Signal("価格履歴なし (ATL近接判定不可)", 0, SignalKind.ATL_STABLE_UNKNOWN)
        }
        val low = prices.min()
        val high = prices.max()
        if (high == low) return Signal("価格安定 (ATL近接判定不可)", 0, SignalKind.ATL_STABLE_UNKNOWN)
        val position = (current - low).toDouble() / max(1, high - low)
        return when {
            position <= 0 -> Signal("過去最安値到達", 30, SignalKind.ATL_REACHED)
            position <= 0.1 -> Signal("過去最安値圏", 22, SignalKind.ATL_NEAR)
            position <= 0.3 -> Signal("最安値近辺", 12, SignalKind.PRICE_LOW_RANGE)
            position >= 0.9 -> Signal("過去最高値圏", -15, SignalKind.PRICE_HIGH_RANGE)
            else -> Signal("中間価格帯", 0, SignalKind.PRICE_MID_RANGE)
        }
    }

    private fun signalTrend(history: List<PriceRecord>): Signal {
        val pred = PricePredictionEngine.predict(history)
            ?: return Signal("トレンド判定不可", 0, SignalKind.TREND_UNKNOWN)
        val current = history.last().realPrice
        if (current == 0L) return Signal("価格ゼロ", 0, SignalKind.PRICE_ZERO)
        val futureRatio = (pred.predicted30d - current).toDouble() / current
        return when {
            futureRatio > 0.05 -> Signal("価格上昇中 (待ちは不利)", 10, SignalKind.TREND_UP)
            futureRatio > 0.01 -> Signal("微上昇", 3, SignalKind.TREND_UP_SLIGHT)
            futureRatio < -0.05 -> Signal("価格下降中 (待ちが有利)", -15, SignalKind.TREND_DOWN)
            futureRatio < -0.01 -> Signal("微下降", -5, SignalKind.TREND_DOWN_SLIGHT)
            else -> Signal("価格横ばい", 0, SignalKind.TREND_FLAT)
        }
    }

    private fun signalDiscountFromList(current: Long, listPrice: Long): Signal {
        if (listPrice <= 0 || listPrice <= current) return Signal("割引なし", 0, SignalKind.NO_DISCOUNT)
        val discountPct = (listPrice - current).toDouble() / listPrice * 100
        val pct = discountPct.toInt()
        return when {
            discountPct >= 40 -> Signal("定価比${pct}%OFF", 15, SignalKind.DISCOUNT_PCT, listOf(pct))
            discountPct >= 25 -> Signal("定価比${pct}%OFF", 10, SignalKind.DISCOUNT_PCT, listOf(pct))
            discountPct >= 10 -> Signal("定価比${pct}%OFF", 5, SignalKind.DISCOUNT_PCT, listOf(pct))
            else -> Signal("定価比${pct}%OFF (僅少)", 1, SignalKind.DISCOUNT_PCT_MINOR, listOf(pct))
        }
    }

    private fun signalVolatility(history: List<PriceRecord>): Signal {
        // ATL 近接判定と同じ理由で 0 以下を除外する (取得失敗の痕跡であって価格ではない)。
        // ¥0 が 1 件混じると分散が跳ね上がり、安定した系列が安定加点 (+10) を失う。
        val prices = history.map { it.realPrice.toDouble() }.filter { it > 0.0 }
        if (prices.isEmpty()) return Signal("ボラティリティ判定不可", 0, SignalKind.AVG_PRICE_ZERO)
        val mean = prices.average()
        if (mean == 0.0) return Signal("平均価格ゼロ", 0, SignalKind.AVG_PRICE_ZERO)
        val variance = prices.map { (it - mean) * (it - mean) }.average()
        val cv = sqrt(variance) / mean
        return when {
            cv < 0.02 -> Signal("極めて安定", 10, SignalKind.VOLATILITY_VERY_STABLE)
            cv < 0.05 -> Signal("価格安定", 5, SignalKind.VOLATILITY_STABLE)
            cv > 0.25 -> Signal("価格変動大", -5, SignalKind.VOLATILITY_HIGH)
            else -> Signal("通常の価格変動", 0, SignalKind.VOLATILITY_NORMAL)
        }
    }

    private fun signalHistoryConfidence(history: List<PriceRecord>): Signal {
        val n = history.size
        return when {
            n >= 90 -> Signal("豊富な履歴", 10, SignalKind.HISTORY_ABUNDANT)
            n >= 30 -> Signal("十分な履歴", 5, SignalKind.HISTORY_SUFFICIENT)
            else -> Signal("履歴不足", 0, SignalKind.HISTORY_INSUFFICIENT)
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
        return Signal(
            // truncate-order-ok: 直前の when が ALWAYS_ON_DISCOUNT / INFLATED_LIST_PRICE /
            // PRE_SALE_MARKUP の 3 種だけを names に積む。いずれも Severity.HIGH なので
            // 同順位内の切り捨てであり、深刻な項目が押し出されることはない。
            "ダークパターン検出 (${names.take(2).joinToString("/")})",
            penalty,
            SignalKind.DARK_PATTERN_DETECTED,
        )
    }
}
