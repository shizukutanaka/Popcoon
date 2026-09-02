package io.github.shizukutanaka.popcoon.worker

/**
 * WorkManager の一意ワーク名。
 *
 * **1 箇所に集める理由**: `enqueueUniquePeriodicWork` は名前が衝突すると
 * 既存のスケジュールを黙って置き換える。名前が各 Worker の companion に散っていると
 * コピー & ペーストでの衝突に気付けない。ここに並べておけば重複が目で見えるし、
 * 何より **Android 非依存なので実コンパイルと kotest の検証対象に入る**
 * (WorkManager 依存の Worker 本体に置いていた間は、名前を検証する spec が
 * まるごと実行できなかった)。
 */
object WorkNames {
    /** 日次の価格同期。 */
    const val PRICE_SYNC = "price_sync_daily"

    /** 週次ダイジェスト。 */
    const val WEEKLY_DIGEST = "weekly_digest"
}
