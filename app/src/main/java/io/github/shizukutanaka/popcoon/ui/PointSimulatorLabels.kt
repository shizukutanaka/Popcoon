package io.github.shizukutanaka.popcoon.ui

import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.feature.points.PointSimulator

/**
 * [PointSimulator.PointSource] → ローカライズ済み文字列リソース ID。
 *
 * PointSimulator.kt は popcoon-tdd/kotlin_parity/run_points.sh が Android SDK 無しで
 * 直接コンパイルする対象のため Android/R 依存を持ち込めない。このマッピングはその制約が
 * 無い UI 層に置く — ui/DarkPatternLabels.kt / ui/SaleCalendarLabels.kt /
 * ui/BuyScoreSignalLabels.kt と同じ方針。
 */
fun PointSimulator.PointSource.nameRes(): Int = when (kind) {
    PointSimulator.Kind.RAKUTEN_SPU -> R.string.points_rakuten_spu
    PointSimulator.Kind.RAKUTEN_5_0_DAY -> R.string.points_rakuten_5_0_day
    PointSimulator.Kind.RAKUTEN_DIAMOND -> R.string.points_rakuten_diamond
    PointSimulator.Kind.YAHOO_PAYPAY_BASE -> R.string.points_yahoo_paypay_base
    PointSimulator.Kind.YAHOO_5_DAY -> R.string.points_yahoo_5_day
    PointSimulator.Kind.YAHOO_SUNDAY -> R.string.points_yahoo_sunday
    PointSimulator.Kind.YAHOO_PREMIUM -> R.string.points_yahoo_premium
    PointSimulator.Kind.YAHOO_SOFTBANK -> R.string.points_yahoo_softbank
    PointSimulator.Kind.YAHOO_THANKS_DAY -> R.string.points_yahoo_thanks_day
    PointSimulator.Kind.AMAZON_POINTS -> R.string.points_amazon_points
}

/**
 * [PointSimulator.YahooRank] → ローカライズ済みランク名の文字列リソース ID。
 * 設定画面のランク選択チップで使う。
 */
fun PointSimulator.YahooRank.toLabelResource(): Int = when (this) {
    PointSimulator.YahooRank.NONE -> R.string.yahoo_rank_none
    PointSimulator.YahooRank.SILVER -> R.string.yahoo_rank_silver
    PointSimulator.YahooRank.GOLD -> R.string.yahoo_rank_gold
}
