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
     * 1 回の同期で出す通知の計画。
     *
     * @property notify 個別通知を出す Drop (優先度順)。
     * @property suppressed 上限を超えて個別通知に載らなかった Drop (優先度順)。
     *   **捨ててはならない** — 1 件のまとめ通知で件数と商品名を伝える。
     */
    data class Plan(
        val notify: List<Drop>,
        val suppressed: List<Drop>,
    )

    /**
     * 通知する Drop を優先度順に選び、個別通知は [maxNotifications] 件に絞る。
     * 目標価格到達を最優先、次に値下がり率が大きい順 (arXiv PMC8523513: 過剰な通知は
     * 割り込み負荷となりUXを損なうため上限を設ける)。
     *
     * **上限超過分を黙って捨てないこと**が本関数の要件 (ソクラテス式レビューで発見):
     * 旧 `selectNotifications` は上限超過分を `take()` で切り落としていたが、呼び出し側の
     * PriceSyncWorker は切り落とし前に **全 Drop の確定価格を DB へ書き戻している**。
     * 基準価格 (`WatchlistItem.realPrice`) が下がった後は
     * `PriceAlertEvaluator` のエッジトリガも `dropPercent` 判定も再発火しないため、
     * 4 件目以降の「目標価格到達」は**二度と通知されず永久に失われていた**
     * (楽天スーパーセール等、同日に複数商品が同時に値下がりする状況で現実に起きる)。
     * 割り込み回数の上限は維持したまま情報損失だけを無くすため、超過分は
     * [Plan.suppressed] として返し、呼び出し側が 1 件のまとめ通知に集約する
     * (Android の通知グループ + サマリと同じ考え方: 抑制するのは割り込みであって情報ではない)。
     */
    fun plan(drops: List<Drop>, maxNotifications: Int): Plan {
        val ordered = drops
            .sortedWith(compareByDescending<Drop> { it.targetReached }.thenByDescending { it.pct })
        val limit = maxNotifications.coerceAtLeast(0)
        return Plan(notify = ordered.take(limit), suppressed = ordered.drop(limit))
    }

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
