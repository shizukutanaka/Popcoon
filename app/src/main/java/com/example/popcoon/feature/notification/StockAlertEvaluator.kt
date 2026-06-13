package com.example.popcoon.feature.notification

/**
 * 在庫変化アラート判定の純関数。【現状: 休眠中 — 実データ供給待ち】
 *
 * 「在庫アラート」は競合アプリ（Keepa, CamelCamelCamel, Pricewise 等）が普遍的に持つ機能。
 *
 * ⚠ 重要 (ソクラテス監査 2026-06 で判明):
 *  `Product.stockCount` フィールドは存在するが、本番のどのデータ経路
 *  (`AmazonPaApiClient` / `RakutenClient` / `YahooClient` / `FallbackScraper`) でも
 *  代入されず、常に null である。backend の `PriceRecord` にも在庫フィールドが無い。
 *  → 在庫の「真の信号」がパイプラインに流れていないため、本関数は意図的に
 *    どこからも呼び出していない (UI トグル・Worker 配線・Room 列は配線せず)。
 *  同根の死蔵: `SortAndFilter` の「在庫切れ除外」フィルタ (`stockCount == 0`) も同様に不発。
 *
 *  本関数自体のロジックは正しく検証済み (StockAlertEvaluatorTest)。
 *  将来 scraper/backend が実在庫 (在庫数 or in_stock 真偽) を返すようになれば、
 *  そこを `currentlyInStock` に渡すだけで即有効化できる、という設計上のフックとして残す。
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
