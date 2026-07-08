package io.github.shizukutanaka.popcoon.feature.watchlist

/**
 * ウォッチ追加時からの価格変動（純関数）。
 *
 * 同種の価格追跡アプリ（CamelCamelCamel 等）が備える「追加時からいくら動いたか」を
 * 表示するためのコアロジック。`WatchlistItem.addedPrice`（追加時に固定）と現在価格から
 * 変動額・変動率・方向を算出する。
 *
 * Android 非依存の純関数 → 単体テストで網羅検証できる。
 */
object WatchlistPriceDelta {

    enum class Direction { DOWN, UP, FLAT }

    data class Delta(
        /** 符号付き変動額（円）。負 = 値下がり。 */
        val amount: Long,
        /** 符号付き変動率（%、追加時基準、整数切り捨て）。 */
        val percent: Int,
        val direction: Direction,
    ) {
        /** 表示用の絶対額。 */
        val absAmount: Long get() = kotlin.math.abs(amount)

        /** 表示用の絶対率。 */
        val absPercent: Int get() = kotlin.math.abs(percent)
    }

    /**
     * @param addedPrice ウォッチ追加時の価格（円）。0 以下 = 基準なし。
     * @param currentPrice 現在価格（円）。
     * @return 変動。基準・現在価格が無効なら null（表示しない）。
     */
    fun since(addedPrice: Long, currentPrice: Long): Delta? {
        if (addedPrice <= 0 || currentPrice <= 0) return null
        val amount = currentPrice - addedPrice
        val percent = (amount * 100 / addedPrice).toInt()
        val direction = when {
            amount < 0 -> Direction.DOWN
            amount > 0 -> Direction.UP
            else -> Direction.FLAT
        }
        return Delta(amount = amount, percent = percent, direction = direction)
    }
}
