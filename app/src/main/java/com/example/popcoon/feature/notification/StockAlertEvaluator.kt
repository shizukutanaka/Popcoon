package com.example.popcoon.feature.notification

/**
 * 在庫変化アラート判定の純関数。
 *
 * 「在庫アラート」は競合アプリ（Keepa, CamelCamelCamel, Pricewise 等）が普遍的に持つ機能で、
 * Popcoon には Product.stockCount フィールドが既に存在するが、
 * 状態変化の追跡と通知導線が実装されていなかった。
 *
 * 価格アラート (PriceAlertEvaluator) と直交して動作する:
 *  - 価格変化がなくても在庫復活は通知する
 *  - 在庫切れ通知はオプション (頻繁な入出荷で大量通知になるリスクを避けるため、デフォルト: 有効)
 *
 * Android 非依存の純関数 → 単体テストで網羅検証できる。
 */
object StockAlertEvaluator {

    enum class Kind {
        /** 在庫なし → あり (最優先: 買いたくて待っていたユーザーへの通知) */
        BACK_IN_STOCK,
        /** 在庫あり → なし */
        OUT_OF_STOCK,
        /** 変化なし / アラート無効 / 初回同期 (前回状態不明) */
        NONE,
    }

    /**
     * @param previouslyInStock 前回同期時の在庫状態。null = 初回同期 (比較基準なし)。
     * @param currentlyInStock  今回取得した在庫状態 (`Product.isInStock`)。
     * @param stockAlertEnabled ユーザーがこの商品の在庫アラートを有効にしているか。
     */
    fun evaluate(
        previouslyInStock: Boolean?,
        currentlyInStock: Boolean,
        stockAlertEnabled: Boolean,
    ): Kind {
        if (!stockAlertEnabled) return Kind.NONE
        if (previouslyInStock == null) return Kind.NONE  // 初回同期: 前回状態なし

        return when {
            !previouslyInStock && currentlyInStock -> Kind.BACK_IN_STOCK
            previouslyInStock && !currentlyInStock -> Kind.OUT_OF_STOCK
            else -> Kind.NONE
        }
    }
}
