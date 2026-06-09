package com.example.popcoon.feature.watchlist

import com.example.popcoon.data.db.WatchlistItem

/**
 * ウォッチリストの並べ替え（純関数）。
 *
 * 同種の価格追跡アプリ（Karma 等）が備える「ウォッチリストの整理」に相当。
 * 従来 Popcoon は追加日降順固定だったが、ユーザーが安い順・割引率順・
 * 目標到達順などで見たいケースは多い。
 *
 * Android 非依存の純関数 → 単体テストで網羅検証できる。
 * すべてのモードは最終タイブレークに productKey を使い、完全に決定的。
 */
object WatchlistSort {

    enum class Mode {
        /** 追加日が新しい順（既定 = 従来挙動） */
        ADDED_DESC,

        /** 現在価格が安い順 */
        PRICE_ASC,

        /** 現在価格が高い順 */
        PRICE_DESC,

        /** 参考価格からの割引率が大きい順 */
        DISCOUNT_DESC,

        /** 商品名の昇順（大小無視） */
        NAME_ASC,

        /** 目標価格への近さ順（到達済み → 近い順、未設定は末尾） */
        TARGET_PROGRESS,
    }

    /** 参考価格からの割引率（0.0–1.0）。listPrice が realPrice 以下なら 0。 */
    private fun discountFraction(item: WatchlistItem): Double =
        if (item.listPrice > item.realPrice && item.listPrice > 0) {
            (item.listPrice - item.realPrice).toDouble() / item.listPrice
        } else {
            0.0
        }

    private fun hasTarget(item: WatchlistItem): Boolean =
        item.targetPrice != null && item.targetPrice > 0

    /** 目標価格に対する現在価格の比率。小さいほど目標に近い／到達済み。 */
    private fun targetRatio(item: WatchlistItem): Double {
        val target = item.targetPrice ?: return Double.MAX_VALUE
        if (target <= 0) return Double.MAX_VALUE
        return item.realPrice.toDouble() / target
    }

    fun sort(items: List<WatchlistItem>, mode: Mode): List<WatchlistItem> {
        val byKey = compareBy<WatchlistItem> { it.productKey } // 決定的タイブレーク
        val comparator = when (mode) {
            Mode.ADDED_DESC ->
                compareByDescending<WatchlistItem> { it.addedAt }.then(byKey)

            Mode.PRICE_ASC ->
                compareBy<WatchlistItem> { it.realPrice }
                    .thenByDescending { it.addedAt }.then(byKey)

            Mode.PRICE_DESC ->
                compareByDescending<WatchlistItem> { it.realPrice }
                    .thenByDescending { it.addedAt }.then(byKey)

            Mode.DISCOUNT_DESC ->
                compareByDescending<WatchlistItem> { discountFraction(it) }
                    .thenByDescending { it.addedAt }.then(byKey)

            Mode.NAME_ASC ->
                compareBy<WatchlistItem> { it.title.lowercase() }.then(byKey)

            Mode.TARGET_PROGRESS ->
                // 目標設定済みを先に、その中で目標比の小さい（近い/到達済み）順。
                compareByDescending<WatchlistItem> { hasTarget(it) }
                    .thenBy { targetRatio(it) }
                    .thenByDescending { it.addedAt }.then(byKey)
        }
        return items.sortedWith(comparator)
    }
}
