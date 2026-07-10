package io.github.shizukutanaka.popcoon.ui

import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer

/**
 * [BuyTimingScorer.Signal] → ローカライズ済み文字列リソース ID + フォーマット引数。
 *
 * BuyTimingScorer.kt は popcoon-tdd/kotlin_parity/run.sh が Android SDK 無しで直接
 * コンパイルする対象のため Android/R 依存を持ち込めない。このマッピングはその制約が無い
 * UI 層に置く — ui/DarkPatternLabels.kt / ui/SaleCalendarLabels.kt と同じ方針。
 *
 * SignalKind.NONE (Signal("", 0) センチネル) は呼び出し元 (ScoreCard.kt) が
 * `contribution != 0 && name.isNotEmpty()` で事前にフィルタするため、ここでは
 * 呼ばれない想定 — 万一呼ばれても安全なフォールバックとして空文字列相当を返す。
 */
fun BuyTimingScorer.Signal.toLabelResource(): Pair<Int, List<Any>> = when (kind) {
    BuyTimingScorer.SignalKind.NONE -> R.string.buy_score_neutral_base to emptyList() // 到達しない想定
    BuyTimingScorer.SignalKind.NEUTRAL_BASE -> R.string.buy_score_neutral_base to emptyList()
    BuyTimingScorer.SignalKind.SCORE_NORMALIZED -> R.string.buy_score_normalized to emptyList()
    BuyTimingScorer.SignalKind.SALE_IMMINENT -> R.string.buy_score_sale_imminent to emptyList()
    BuyTimingScorer.SignalKind.SALE_APPROACHING -> R.string.buy_score_sale_approaching to emptyList()
    BuyTimingScorer.SignalKind.DOW_CHEAP -> R.string.buy_score_dow_cheap to emptyList()
    BuyTimingScorer.SignalKind.DOW_EXPENSIVE -> R.string.buy_score_dow_expensive to emptyList()
    BuyTimingScorer.SignalKind.ATL_STABLE_UNKNOWN -> R.string.buy_score_atl_stable_unknown to emptyList()
    BuyTimingScorer.SignalKind.ATL_REACHED -> R.string.buy_score_atl_reached to emptyList()
    BuyTimingScorer.SignalKind.ATL_NEAR -> R.string.buy_score_atl_near to emptyList()
    BuyTimingScorer.SignalKind.PRICE_LOW_RANGE -> R.string.buy_score_price_low_range to emptyList()
    BuyTimingScorer.SignalKind.PRICE_HIGH_RANGE -> R.string.buy_score_price_high_range to emptyList()
    BuyTimingScorer.SignalKind.PRICE_MID_RANGE -> R.string.buy_score_price_mid_range to emptyList()
    BuyTimingScorer.SignalKind.TREND_UNKNOWN -> R.string.buy_score_trend_unknown to emptyList()
    BuyTimingScorer.SignalKind.PRICE_ZERO -> R.string.buy_score_price_zero to emptyList()
    BuyTimingScorer.SignalKind.TREND_UP -> R.string.buy_score_trend_up to emptyList()
    BuyTimingScorer.SignalKind.TREND_UP_SLIGHT -> R.string.buy_score_trend_up_slight to emptyList()
    BuyTimingScorer.SignalKind.TREND_DOWN -> R.string.buy_score_trend_down to emptyList()
    BuyTimingScorer.SignalKind.TREND_DOWN_SLIGHT -> R.string.buy_score_trend_down_slight to emptyList()
    BuyTimingScorer.SignalKind.TREND_FLAT -> R.string.buy_score_trend_flat to emptyList()
    BuyTimingScorer.SignalKind.NO_DISCOUNT -> R.string.buy_score_no_discount to emptyList()
    BuyTimingScorer.SignalKind.DISCOUNT_PCT -> R.string.buy_score_discount_pct to kindArgs
    BuyTimingScorer.SignalKind.DISCOUNT_PCT_MINOR -> R.string.buy_score_discount_pct_minor to kindArgs
    BuyTimingScorer.SignalKind.AVG_PRICE_ZERO -> R.string.buy_score_avg_price_zero to emptyList()
    BuyTimingScorer.SignalKind.VOLATILITY_VERY_STABLE -> R.string.buy_score_volatility_very_stable to emptyList()
    BuyTimingScorer.SignalKind.VOLATILITY_STABLE -> R.string.buy_score_volatility_stable to emptyList()
    BuyTimingScorer.SignalKind.VOLATILITY_HIGH -> R.string.buy_score_volatility_high to emptyList()
    BuyTimingScorer.SignalKind.VOLATILITY_NORMAL -> R.string.buy_score_volatility_normal to emptyList()
    BuyTimingScorer.SignalKind.HISTORY_ABUNDANT -> R.string.buy_score_history_abundant to emptyList()
    BuyTimingScorer.SignalKind.HISTORY_SUFFICIENT -> R.string.buy_score_history_sufficient to emptyList()
    BuyTimingScorer.SignalKind.HISTORY_INSUFFICIENT -> R.string.buy_score_history_insufficient to emptyList()
    BuyTimingScorer.SignalKind.DARK_PATTERN_DETECTED -> R.string.buy_score_dark_pattern_detected to emptyList()
}
