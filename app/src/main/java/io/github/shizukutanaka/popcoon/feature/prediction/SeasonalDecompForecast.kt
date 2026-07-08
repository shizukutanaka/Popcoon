package io.github.shizukutanaka.popcoon.feature.prediction

/**
 * trend+seasonal 分解予測（DLinear 風の軽量版）。
 * 中心移動平均(窓=period)で季節成分を分離し、季節除去系列に最小二乗線形を当てて外挿する。
 * EC 価格の週次季節性（週末・5と0のつく日等）を捉え、Holt 線形より正確に「○日後が安い」を予測。
 *
 * Python 参照 (popcoon-tdd/proto_seasonal_decomp_forecast.py) と完全一致。
 * パリティは SeasonalDecompForecastTest（ゴールデンベクタ）で保証（PORTING_SPEC.md A1）。
 * PricePredictionEngine への統合は後続（本オブジェクトは独立ユーティリティ、既存挙動は不変）。
 */
object SeasonalDecompForecast {

    /**
     * @param history 価格履歴（古い→新しい、等間隔・日次想定）。
     * @param horizon 予測する先の本数。
     * @param period 季節周期（週次なら 7）。1 以下なら季節性なし（純線形）。
     * @param minHistory これ未満は直近値フラット。既定 max(2*period, 4)。
     */
    fun forecast(
        history: List<Double>,
        horizon: Int = 7,
        period: Int = 7,
        minHistory: Int? = null,
    ): List<Double> {
        val n = history.size
        if (n == 0) return emptyList()
        val minH = minHistory ?: maxOf(2 * period, 4)

        if (n < minH || period <= 1) {
            if (n < minH) return List(horizon) { history.last() }
            // period<=1: 季節性なしの純線形
            val (a, b) = linreg((0 until n).map { it.toDouble() }, history)
            return (0 until horizon).map { s -> a * (n + s) + b }
        }

        // 1. 中心移動平均で trend を推定 → 残差の位相別平均 = seasonal
        val half = period / 2
        val sums = DoubleArray(period)
        val counts = IntArray(period)
        for (i in 0 until n) {
            val start = i - half
            val end = start + period
            if (start >= 0 && end <= n) {
                var ma = 0.0
                for (j in start until end) ma += history[j]
                ma /= period
                val k = i % period
                sums[k] += history[i] - ma
                counts[k] += 1
            }
        }
        // 2. seasonal を総和0に中心化
        val seasonal = DoubleArray(period) { k -> if (counts[k] > 0) sums[k] / counts[k] else 0.0 }
        val meanSeasonal = seasonal.sum() / period
        for (k in 0 until period) seasonal[k] -= meanSeasonal

        // 3. 季節除去系列に線形トレンド → 4. 予測
        val deseason = (0 until n).map { i -> history[i] - seasonal[i % period] }
        val (a, b) = linreg((0 until n).map { it.toDouble() }, deseason)
        return (0 until horizon).map { s ->
            val t = n + s
            a * t + b + seasonal[t % period]
        }
    }

    /** 最小二乗 (slope, intercept)。分母0なら (0, mean)。 */
    private fun linreg(xs: List<Double>, ys: List<Double>): Pair<Double, Double> {
        val n = xs.size
        val sx = xs.sum()
        val sy = ys.sum()
        val sxx = xs.sumOf { it * it }
        // Python の zip() と同じく短い方に合わせて切り詰める (parity + IOOBE 防止)。
        val sxy = xs.zip(ys).sumOf { (x, y) -> x * y }
        val denom = n * sxx - sx * sx
        if (denom == 0.0) return 0.0 to (if (n > 0) sy / n else 0.0)
        val a = (n * sxy - sx * sy) / denom
        val b = (sy - a * sx) / n
        return a to b
    }
}
