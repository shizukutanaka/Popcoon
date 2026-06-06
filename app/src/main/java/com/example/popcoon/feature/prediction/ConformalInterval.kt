package com.example.popcoon.feature.prediction

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Split-conformal 予測区間。分布フリーの**被覆保証付き** margin を返す。
 *
 * Python 参照 (popcoon-tdd/proto_conformal_interval.py) と完全一致。
 * パリティは ConformalIntervalTest（ゴールデンベクタ）で保証する（PORTING_SPEC.md A6）。
 *
 * 理論: 交換可能なキャリブレーション残差に対し P(|Y - point| <= margin) >= 1 - alpha。
 * 現状 PricePredictionEngine.predictionMargin は RMSE ベース（被覆保証なし）。本オブジェクトは
 * その置換候補で、まずは独立ユーティリティとして追加（既存挙動は不変、配線は後続）。
 */
object ConformalInterval {

    data class Interval(val low: Double, val high: Double, val margin: Double)

    /**
     * 絶対残差の split-conformal 分位点 (= 区間半幅)。
     * residuals が空なら 0.0。alpha は (0,1)。
     */
    fun conformalMargin(residuals: List<Double>, alpha: Double = 0.1): Double {
        require(residuals.isEmpty() || (alpha > 0.0 && alpha < 1.0)) {
            "alpha must be in (0,1)"
        }
        if (residuals.isEmpty()) return 0.0
        val absRes = residuals.map { abs(it) }.sorted()
        val n = absRes.size
        val k = ceil((n + 1) * (1.0 - alpha)).toInt()
        return if (k > n) absRes[n - 1] else absRes[k - 1]
    }

    /** 点予測 point に対する被覆保証付き区間。 */
    fun predictInterval(
        point: Double,
        residuals: List<Double>,
        alpha: Double = 0.1,
    ): Interval {
        val m = conformalMargin(residuals, alpha)
        return Interval(point - m, point + m, m)
    }
}
