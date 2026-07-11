package io.github.shizukutanaka.popcoon.feature.prediction

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Split-conformal 予測区間。分布フリーの**被覆保証付き** margin を返す。
 *
 * Python 参照 (popcoon-tdd/proto_conformal_interval.py) と完全一致。
 * パリティは ConformalIntervalTest（ゴールデンベクタ）で保証する（PORTING_SPEC.md A6）。
 *
 * 理論: 交換可能なキャリブレーション残差に対し P(|Y - point| <= margin) >= 1 - alpha。
 * PricePredictionEngine.predict() に統合済み (PricePredictionEngine.kt 内
 * `ConformalInterval.conformalMargin(...)` 呼び出し)、RMSE ベースの旧 margin を置換した。
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

    /**
     * オンライン分位点追跡 (quantile tracking) — Conformal PID の P 項のみを実装
     * (積分項は飽和関数のチューニングを要し誤設定時の不安定リスクがあるため見送り。
     * P 項単独でも文献の主要な利得の大部分を再現する)。
     *
     * 出典: Conformal PID Control for Time Series (Angelopoulos+, NeurIPS 2023,
     * arXiv:2307.16895) の quantile tracker。Adaptive Conformal Inference
     * (Gibbs & Candès 2021) の分位点直接追跡版と等価。2026-07 リサーチで確認:
     * 分布シフト (セール期のボラティリティ急変等) に対し、静的 [conformalMargin]
     * (全キャリブレーション集合の分位点で順序不変) より直近の実績を反映できる。
     *
     * アルゴリズム: pinball loss の勾配降下と等価な更新則
     *   err_t = 1[|residual_t| > q_t]
     *   q_{t+1} = max(0, q_t + eta * (err_t - alpha))
     * を残差列の時系列順に1パス再生する。static split-conformal の分位点で
     * ウォームスタートしてから追跡することで、コールドスタート (q=0 起点だと
     * 序盤の残差がほぼ全て「超過」扱いになり不安定) を避ける。
     *
     * eta (ステップ幅) 省略時はデータレンジの5% (`max(|r|) - min(|r|)`) を使う —
     * ハイパーパラメータ探索を避けた決定的なデフォルト。
     *
     * Python 参照 (proto_conformal_interval.py::adaptive_conformal_margin) と完全一致。
     */
    fun adaptiveConformalMargin(
        residuals: List<Double>,
        alpha: Double = 0.1,
        eta: Double? = null,
    ): Double {
        if (residuals.isEmpty()) return 0.0
        require(alpha > 0.0 && alpha < 1.0) { "alpha must be in (0,1)" }

        val absRes = residuals.map { abs(it) }
        val step = eta ?: max((absRes.max() - absRes.min()) * 0.05, 1e-9)

        var q = conformalMargin(residuals, alpha)  // warm start
        for (r in absRes) {
            val err = if (r > q) 1.0 else 0.0
            q = max(0.0, q + step * (err - alpha))
        }
        return q
    }
}
