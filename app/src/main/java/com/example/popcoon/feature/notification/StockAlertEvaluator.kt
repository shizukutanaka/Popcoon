package com.example.popcoon.feature.notification

/**
 * 在庫変化アラート判定の純関数。【現状: 半休眠 — ライブ経路は供給開始、Worker 配線が残課題】
 *
 * 「在庫アラート」は競合アプリ（Keepa, CamelCamelCamel, Pricewise 等）が普遍的に持つ機能。
 *
 * 進捗 (2026-06 更新 — 当初のソクラテス監査時点から状況が変わった):
 *  当初は `Product.stockCount` がどの経路でも常に null だった。その後、ライブ検索経路の
 *  3 プラットフォーム全てで在庫を復元済み:
 *   - `RakutenMapper` (availability → stockCount)
 *   - `YahooMapper` (inStock → stockCount)
 *   - `AmazonPaApiClient` (Offers.Listings.Availability → stockCount, `stockFromAmazonAvailability`)
 *   - `FallbackScraper` (schema.org availability → stockCount)
 *  → `SortAndFilter` の「在庫切れ除外」フィルタはライブ検索結果に対して**機能するようになった**。
 *
 * ⚠ 在庫アラート (本関数) を有効化するための残作業:
 *  ウォッチリスト同期 (`PriceSyncWorker`) は `backend.getPriceHistory` 経由で、PriceRecord に
 *  在庫フィールドが無いため「経時の在庫状態」を取得していない。有効化には:
 *   1. Worker がウォッチ商品の**ライブ在庫** (search/詳細取得で得られる stockCount) を取得する
 *   2. `WatchlistItem` に前回 in-stock 状態の列を追加 (Room migration)
 *   3. 商品ごとの在庫アラート有効化トグル (UI)
 *  の 3 点が必要。本関数のロジックは検証済み (StockAlertEvaluatorTest) なので、
 *  `previouslyInStock`/`currentlyInStock` に値を渡すだけで即有効化できる。
 *
 * 想定動作 (有効化後):
 *  - 価格変化がなくても在庫復活は通知する
 *  - 在庫切れ通知はオプション (頻繁な入出荷で大量通知になるリスクを避ける)
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
