package io.github.shizukutanaka.popcoon.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.core.CurrencyFormatter
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.github.shizukutanaka.popcoon.data.db.WatchlistDao
import io.github.shizukutanaka.popcoon.data.repository.BackendClient
import io.github.shizukutanaka.popcoon.data.repository.IProductRepository
import io.github.shizukutanaka.popcoon.feature.notification.LocalNotificationManager
import io.github.shizukutanaka.popcoon.feature.notification.PriceAlertDebouncer
import io.github.shizukutanaka.popcoon.feature.notification.PriceAlertEvaluator
import io.github.shizukutanaka.popcoon.feature.notification.StockAlertEvaluator
import io.github.shizukutanaka.popcoon.feature.retention.ReviewPrompter
import io.github.shizukutanaka.popcoon.widget.WidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import io.github.shizukutanaka.popcoon.feature.settings.UserPreferences

/**
 * バックグラウンド価格同期 Worker。
 *
 * スケジュール: 1日1回、Wi-Fi 接続時のみ、充電中優先。
 * 実行内容:
 *  1. ウォッチリスト全商品の最新価格を取得
 *  2. backend に価格履歴を追記
 *  3. アラート条件を評価 (backend の cron と二重構造で信頼性向上)
 *  4. ウィジェットを更新
 *  5. ReviewPrompter の成功カウンターを加算 (値下がりした場合)
 *
 * 設計原則:
 *  - 失敗しても次回実行まで待つ (Result.retry() で指数バックオフ)
 *  - バッテリー / データ節約を優先 (NetworkType.CONNECTED + setRequiresBatteryNotLow)
 *  - 処理時間上限: 10 分 (WorkManager の制約)
 */
@HiltWorker
class PriceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val watchlistDao: WatchlistDao,
    private val repository: IProductRepository,
    private val backend: BackendClient,
    private val reviewPrompter: ReviewPrompter,
    private val notificationManager: LocalNotificationManager,
    private val prefs: io.github.shizukutanaka.popcoon.feature.settings.UserPreferences,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        PopcoonLogger.i(this, "価格同期開始 run=$runAttemptCount")
        val minDropPercent = prefs.notifDropPercent.first()
        val watchlist = try {
            watchlistDao.observeAll().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PopcoonLogger.w(this, "ウォッチリスト取得失敗: ${e.message}")
            emptyList()
        }

        if (watchlist.isEmpty()) return Result.success()

        // 各アイテムを並列取得 (最大 MAX_CONCURRENCY 並列)。従来は逐次で、
        // 件数ぶん直列にネットワーク待ちしていた。Result で成否を追跡する。
        val semaphore = Semaphore(MAX_CONCURRENCY)
        val outcomes: List<kotlin.Result<PriceSyncPlanner.Drop?>> = coroutineScope {
            watchlist.map { item ->
                async {
                    semaphore.withPermit {
                        ensureActive()
                        runCatching {
                            val history = backend.getPriceHistory(item.productKey)
                            if (history.isEmpty()) return@runCatching null

                            val latest = history.first()
                            val previousPrice = item.realPrice

                            // 目標価格到達 / 有意な値下がりを純関数で判定。1同期サイクル遅延確認
                            // (PriceAlertDebouncer) を経由することで、瞬間的なスクレイピングエラー
                            // による誤通知を防ぐ — 通知に値する変化は次回同期で同じ値が再現した
                            // 場合のみ発火する (機能過不足監査で発見)。
                            // 目標到達は率に関係なく最優先で通知（ユーザーが明示的に求めた情報）。
                            // (arXiv 2509.02458: 経験的閾値で値下がり通知の頻度を制御)
                            val resolution = PriceAlertDebouncer.resolve(
                                previousPrice = previousPrice,
                                latestPrice = latest.realPrice,
                                targetPrice = item.targetPrice,
                                minDropPercent = minDropPercent,
                                pendingPrice = item.pendingPrice,
                            )
                            watchlistDao.updatePriceAndPending(
                                item.productKey, resolution.resolvedPrice, resolution.newPendingPrice,
                            )

                            val alert = resolution.alert
                            if (alert.shouldNotify) {
                                PriceSyncPlanner.Drop(
                                    productKey = item.productKey,
                                    title = item.title,
                                    targetPrice = item.targetPrice,
                                    latest = resolution.resolvedPrice,
                                    prev = previousPrice,
                                    pct = alert.dropPercent,
                                    targetReached =
                                        alert.kind == PriceAlertEvaluator.Kind.TARGET_REACHED,
                                )
                            } else {
                                null
                            }
                        }.onFailure { e ->
                            if (e is CancellationException) throw e
                            PopcoonLogger.w(this@PriceSyncWorker, "履歴取得失敗: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
        }
        val drops = outcomes.mapNotNull { it.getOrNull() }
        val failureCount = outcomes.count { it.isFailure }

        // 目標価格到達を最優先、次に値下がり率が大きい順。個別通知は最大 MAX_NOTIFICATIONS 件で、
        // 超過分は捨てずに 1 件のまとめ通知へ回す (確定価格は既に DB へ書き戻し済みなので、
        // ここで落とすと二度と再通知されない)。
        val plan = PriceSyncPlanner.plan(drops, MAX_NOTIFICATIONS)
        // ReviewPrompter に渡す「ユーザーにとって良いことが起きた回数」は、まとめ通知に
        // 回った分も含めた確定値下がり件数。個別通知の上限は割り込みの制御であって、
        // 起きた事実の件数ではない。
        val priceDropCount = plan.notify.size + plan.suppressed.size
        plan.notify
            .forEach { drop ->
                val title = if (drop.targetReached) {
                    applicationContext.getString(
                        R.string.notif_target_reached,
                        CurrencyFormatter.yen(drop.targetPrice ?: drop.latest),
                    )
                } else {
                    applicationContext.getString(R.string.notif_price_drop, drop.pct)
                }
                notificationManager.sendPriceAlert(
                    context = applicationContext,
                    productKey = drop.productKey,
                    title = title,
                    priceText = applicationContext.getString(
                        R.string.notif_price_detail,
                        drop.title.take(20),
                        CurrencyFormatter.yen(drop.latest),
                        CurrencyFormatter.yen(drop.prev),
                    ),
                )
            }
        if (plan.suppressed.isNotEmpty()) {
            notificationManager.sendPriceDropSummary(
                context = applicationContext,
                suppressedCount = plan.suppressed.size,
                // truncate-order-ok: PriceSyncPlanner.plan() が
                // 「目標到達 → 下落率」の順に並べた残りを suppressed として返す。
                titles = plan.suppressed.take(SUMMARY_TITLE_LIMIT).map { it.title },
            )
        }

        // ── 在庫アラートフェーズ ──────────────────────────────────────────────
        // stockAlertEnabled な商品のみ repository.refresh() でライブ在庫を取得する。
        // 価格フェーズとは別の parallel block で処理し、価格同期の失敗と独立させる。
        val stockAlertItems = watchlist.filter { it.stockAlertEnabled }
        if (stockAlertItems.isNotEmpty()) {
            coroutineScope {
                stockAlertItems.map { item ->
                    async {
                        semaphore.withPermit {
                            ensureActive()
                            runCatching {
                                // productKey = "platform:sku" から最小 Product を構築してリフレッシュ
                                val parts = item.productKey.split(":", limit = 2)
                                val platform = io.github.shizukutanaka.popcoon.data.model.Platform
                                    .fromId(parts.getOrNull(0))
                                val sku = parts.getOrNull(1) ?: item.productKey
                                val minProduct = io.github.shizukutanaka.popcoon.data.model.Product(
                                    sku = sku,
                                    title = item.title,
                                    platform = platform,
                                    realPrice = item.realPrice,
                                    listPrice = item.listPrice,
                                    url = item.url,
                                )
                                val fresh = repository.refresh(minProduct) ?: return@runCatching
                                val currentlyInStock = fresh.isInStock
                                val kind = StockAlertEvaluator.evaluate(
                                    previouslyInStock = item.previousInStock,
                                    currentlyInStock = currentlyInStock,
                                    stockAlertEnabled = true,
                                )
                                if (kind == StockAlertEvaluator.Kind.BACK_IN_STOCK) {
                                    notificationManager.sendStockAlert(
                                        context = applicationContext,
                                        productKey = item.productKey,
                                        productTitle = item.title,
                                    )
                                }
                                watchlistDao.updateStockState(item.productKey, currentlyInStock)
                            }.onFailure { e ->
                                if (e is CancellationException) throw e
                                PopcoonLogger.w(this@PriceSyncWorker, "在庫チェック失敗 ${item.productKey}: ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // ウィジェット更新
        // update() は連続タップ UI 向けの 500ms デバウンス版 — バックグラウンドワーカーは
        // この呼び出しの後すぐプロセスがアイドル/終了しうるため、デバウンス中に更新が
        // 破棄される恐れがある (機能過不足監査で発見)。ワーカーからは即時実行版を使う。
        try {
            val updated = watchlistDao.observeAll().first()
            WidgetUpdater.updateImmediate(applicationContext, updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PopcoonLogger.w(this, "ウィジェット更新失敗: ${e.message}")
        }

        // 値下がりがあれば成功イベントを記録 (ReviewPrompter 用)
        if (priceDropCount > 0) {
            repeat(priceDropCount) { reviewPrompter.recordSuccess() }
        }

        // 全件失敗 (backend ダウン等の一過性障害) のときだけ retry し、指数バックオフに乗せる。
        // 一部成功時は upsert 済みのため再実行すると重複通知の恐れ → success で次回日次に委ねる。
        val retry = PriceSyncPlanner.shouldRetry(
            failureCount = failureCount,
            totalCount = watchlist.size,
            runAttemptCount = runAttemptCount,
            maxRetryAttempts = MAX_RETRY_ATTEMPTS,
        )
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        // WORK_NAME は WorkNames.PRICE_SYNC へ移動 (衝突を目で見えるようにするため)。
        /** 1回の同期で送る**個別**通知の上限 (過剰通知防止 — arXiv PMC8523513)。
         *  超過分は 1 件のまとめ通知に集約する (情報は捨てない)。 */
        private const val MAX_NOTIFICATIONS = 3
        /** まとめ通知の本文に列挙する商品名の最大件数 (通知本文の可読性の上限)。 */
        private const val SUMMARY_TITLE_LIMIT = 5
        // 最小値下がり率は UserPreferences.notifDropPercent (既定 3%) が唯一の供給源。
        // 以前ここにあった MIN_DROP_PERCENT 定数は誰からも読まれない死んだ値だった。
        /** 価格取得の最大並列数 (backend への thundering herd 抑制) */
        private const val MAX_CONCURRENCY = 8
        /** 全件失敗時の最大 retry 回数 (指数バックオフ) */
        private const val MAX_RETRY_ATTEMPTS = 3

        /**
         * 日次同期をスケジュール (アプリ起動時に呼ぶ)。
         * ExistingPeriodicWorkPolicy.KEEP で既存スケジュールを保護。
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)  // Wi-Fi のみ (データ通信節約)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)                  // ストレージ枯渇時はスキップ
                .build()

            val request = PeriodicWorkRequestBuilder<PriceSyncWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 4,
                flexTimeIntervalUnit = TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                // 指数バックオフ: 失敗時 30s → 1m → 2m → ...
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    java.util.concurrent.TimeUnit.SECONDS.toMillis(30),
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WorkNames.PRICE_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WorkNames.PRICE_SYNC)
        }
    }
}
