package com.example.popcoon.ui.screens.search

import com.example.popcoon.R
import com.example.popcoon.data.model.Platform

/**
 * 検索結果のソート方針。
 *
 * Apple HIG: ユーザーが「自分に合った順序」で見られるほどエンゲージメントが高まる。
 * 競合調査:
 *  - Keepa: 価格昇順のみ
 *  - プライシー: 価格/送料込み価格
 *  - 最安値.com: 実質価格 / 評価順
 *
 * Popcoon 独自: 買い時スコア降順 (最重要)
 */
enum class SortOption(@androidx.annotation.StringRes val labelRes: Int) {
    BUY_TIMING(R.string.sort_buy_timing),
    PRICE_ASC(R.string.sort_price_asc),
    PRICE_DESC(R.string.sort_price_desc),
    DISCOUNT_DESC(R.string.sort_discount_desc),
    RATING_DESC(R.string.sort_rating_desc),
    ;

    companion object {
        fun apply(rows: List<SearchRow>, option: SortOption): List<SearchRow> = when (option) {
            BUY_TIMING ->
                rows.sortedByDescending { it.score }
            PRICE_ASC ->
                rows.sortedBy { it.product.totalPrice }
            PRICE_DESC ->
                rows.sortedByDescending { it.product.totalPrice }
            DISCOUNT_DESC ->
                rows.sortedByDescending { row ->
                    val list = row.product.listPrice
                    if (list <= 0) 0.0
                    else (list - row.product.realPrice).toDouble() / list
                }
            RATING_DESC ->
                rows.sortedWith(
                    compareByDescending<SearchRow> { it.product.rating ?: Float.NEGATIVE_INFINITY }
                )
        }
    }
}

/**
 * 検索結果フィルタリング。
 *
 * デフォルト: 在庫切れ除外のみ (侵略的フィルタは OFF)。
 * ユーザーが明示的に有効化したフィルタのみ適用する。
 *
 * Apple HIG: フィルタは「見えないものを教えないで」ではなく
 *             「ノイズを取り除く」のが目的。
 */
data class SearchFilter(
    /** 在庫切れを除外 (在庫数 = 0 は除外、null = 不明は含める) */
    val excludeOutOfStock: Boolean = true,
    /** "中古" "訳あり" 等のキーワードを含む商品を除外 */
    val excludeKeywordsEnabled: Boolean = false,
    /** 最低評価閾値 (null = 制限なし) */
    val minRating: Float? = null,
    /** 最低価格 (null = 制限なし) */
    val minPrice: Long? = null,
    /** 最高価格 (null = 制限なし) */
    val maxPrice: Long? = null,
    /** プラットフォーム絞り込み (空 = 全プラットフォーム) */
    val platforms: Set<Platform> = emptySet(),
) {
    private val excludeKeywords = listOf("中古", "訳あり", "ジャンク", "再生品", "B品")

    val isAnyFilterActive: Boolean
        get() = excludeKeywordsEnabled ||
                minRating != null ||
                minPrice != null ||
                maxPrice != null ||
                platforms.isNotEmpty()

    fun apply(rows: List<SearchRow>): List<SearchRow> = rows.filter { row ->
        val p = row.product

        // 在庫切れチェック
        if (excludeOutOfStock && p.stockCount == 0) return@filter false

        // 除外キーワード
        if (excludeKeywordsEnabled && excludeKeywords.any { kw -> p.title.contains(kw) }) {
            return@filter false
        }

        // 最低評価
        minRating?.let { min ->
            val r = p.rating ?: return@filter false
            if (r < min) return@filter false
        }

        // 価格範囲
        minPrice?.let { if (p.totalPrice < it) return@filter false }
        maxPrice?.let { if (p.totalPrice > it) return@filter false }

        // プラットフォーム絞り込み
        if (platforms.isNotEmpty() && p.platform !in platforms) return@filter false

        true
    }
}
