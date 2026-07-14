package io.github.shizukutanaka.popcoon.feature.notification

/**
 * 在庫変化アラート判定の純関数。稼働中。
 *
 * 「在庫アラート」は競合アプリ（Keepa, CamelCamelCamel, Pricewise 等）が普遍的に持つ機能。
 *
 * 稼働経路:
 *  - `WatchlistItem.stockAlertEnabled` (商品ごとの ON/OFF、`WatchlistViewModel.setStockAlertEnabled`
 *    で UI から切替) が true のアイテムのみ `PriceSyncWorker` の在庫アラートフェーズで処理対象になる
 *  - `PriceSyncWorker` が `repository.refresh()` でライブ在庫 (`Product.isInStock`) を取得
 *  - `WatchlistItem.previousInStock` (Room v5 で追加) に前回同期時の在庫状態を保持し、
 *    エッジトリガ判定 (なし→あり の遷移のみ) に本関数を使う
 *  - `RakutenMapper`/`YahooMapper`/`AmazonPaApiClient`/`FallbackScraper` の全ソースが
 *    `Product.stockCount`/`isInStock` を供給する
 *
 * 動作:
 *  - 価格変化がなくても在庫復活 (BACK_IN_STOCK) は通知する
 *  - 在庫切れ (OUT_OF_STOCK) は判定のみ行い、通知は送らない (頻繁な入出荷で大量通知になる
 *    リスクを避けるため — `PriceSyncWorker` は BACK_IN_STOCK のみ通知に使用)
 *
 * Android 非依存の純関数 → 単体テストで網羅検証できる (StockAlertEvaluatorTest)。
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
