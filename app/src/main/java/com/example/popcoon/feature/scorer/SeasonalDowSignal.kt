package com.example.popcoon.feature.scorer

import kotlin.math.round

/**
 * 曜日季節性の買い時シグナル（±maxPoints）。
 * 価格履歴から「今日の曜日は統計的に安い/高い」を学習する純関数。
 *
 * Python 参照 (popcoon-tdd/proto_seasonal_signal.py) と完全一致。
 * パリティは SeasonalDowSignalTest（ゴールデンベクタ）で保証（PORTING_SPEC.md A5）。
 * 丸めは `kotlin.math.round`（round-half-to-even）で Python `round()` と一致。
 *
 * BuyTimingScorer への加算は後続（本オブジェクトは独立ユーティリティ、既存挙動は不変）。
 *
 * @param history (dow, price) のリスト。dow は 0=月 .. 6=日。
 */
object SeasonalDowSignal {

    fun signal(
        history: List<Pair<Int, Double>>,
        todayDow: Int,
        minHistory: Int = 14,
        minDowSamples: Int = 2,
        maxPoints: Int = 10,
    ): Int {
        if (history.size < minHistory) return 0
        val overall = history.sumOf { it.second } / history.size
        if (overall <= 0.0) return 0
        val dowPrices = history.filter { it.first == todayDow }.map { it.second }
        if (dowPrices.size < minDowSamples) return 0
        val dowMean = dowPrices.sum() / dowPrices.size
        // 相対割引: 全体平均より今日の曜日が安いほど正。1% 差 = 1 点、上限 maxPoints。
        val rel = (overall - dowMean) / overall
        val signal = round(rel * 100).toInt()
        return signal.coerceIn(-maxPoints, maxPoints)
    }
}
