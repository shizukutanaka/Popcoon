package io.github.shizukutanaka.popcoon.worker

import io.github.shizukutanaka.popcoon.data.db.WatchlistItem

/**
 * PriceSyncWorker.doWork() の純粋な意思決定ロジック。
 *
 * doWork() 自体は Context (通知送信・ウィジェット更新) と DataStore (UserPreferences) に
 * 直接依存するため plain JVM ユニットテストで実行できず、実行可能テストが皆無だった
 * (機能過不足監査で発見)。StockAlertEvaluator/PriceAlertEvaluator/PriceAlertDebouncer と
 * 同方針で、分岐が濃く回帰しやすい意思決定部分 (通知の優先順位付け・retry 判定) だけを
 * 純粋関数として切り出し、単体テストで検証可能にする。
 * Context に直接触れる部分 (通知送信・ウィジェット更新・WorkManager 制約) は
 * Instrumentation テストに委ねる。
 */
object PriceSyncPlanner {

    data class Drop(
        val item: WatchlistItem,
        val latest: Long,
        val prev: Long,
        val pct: Int,
        val targetReached: Boolean,
    )

    /**
     * 通知する Drop を優先度順に選び、[maxNotifications] 件に絞る。
     * 目標価格到達を最優先、次に値下がり率が大きい順 (arXiv PMC8523513: 過剰な通知は
     * 割り込み負荷となりUXを損なうため上限を設ける)。
     */
    fun selectNotifications(drops: List<Drop>, maxNotifications: Int): List<Drop> =
        drops
            .sortedWith(compareByDescending<Drop> { it.targetReached }.thenByDescending { it.pct })
            .take(maxNotifications)

    /**
     * 全件失敗時のみ retry する。
     * 一部成功時は upsert 済みのため再実行すると重複通知の恐れがあり、success で
     * 次回日次同期に委ねる。[totalCount] が 0 以下の場合は (呼び出し元が空ウォッチリストで
     * 早期 return するため実際には到達しないが) 空ゆえの vacuous な「全滅」判定を避けて
     * false を返す。
     */
    fun shouldRetry(
        failureCount: Int,
        totalCount: Int,
        runAttemptCount: Int,
        maxRetryAttempts: Int,
    ): Boolean = totalCount > 0 && failureCount == totalCount && runAttemptCount < maxRetryAttempts
}
