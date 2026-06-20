package com.example.popcoon.worker

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
import com.example.popcoon.R
import com.example.popcoon.core.CurrencyFormatter
import com.example.popcoon.core.PopcoonLogger
import com.example.popcoon.data.db.WatchlistDao
import com.example.popcoon.data.db.WatchlistItem
import com.example.popcoon.data.repository.BackendClient
import com.example.popcoon.data.repository.IProductRepository
import com.example.popcoon.feature.notification.LocalNotificationManager
import com.example.popcoon.feature.notification.PriceAlertEvaluator
import com.example.popcoon.feature.notification.StockAlertEvaluator
import com.example.popcoon.feature.retention.ReviewPrompter
import com.example.popcoon.widget.WidgetUpdater
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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        PopcoonLogger.i(this, "価格同期開始 run=$runAttemptCount")
        val watchlist = try {
            watchlistDao.observeAll().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PopcoonLogger.w(this, "ウォッチリスト取得失敗: ${e.message}")
            emptyList()
        }

        if (watchlist.isEmpty()) return Result.success()

        var priceDropCount = 0

        // arXiv (PMC8523513) の知見: 過剰な通知は割り込み負荷となりUXを損なう。
        // 1回の同期で送る通知を上限 MAX_NOTIFICATIONS 件に制限し、
        // 目標価格到達 → 値下がり率が大きい順、で優先する。
        data class Drop(
            val item: WatchlistItem,
            val latest: Long,
            val prev: Long,
            val pct: Int,
            val targetReached: Boolean,
        )
        // 各アイテムを並列取得 (最大 MAX_CONCURRENCY 並列)。従来は逐次で、
        // 件数ぶん直列にネットワーク待ちしていた。Result で成否を追跡する。
        val semaphore = Semaphore(MAX_CONCURRENCY)
        val outcomes: List<kotlin.Result<Drop?>> = coroutineScope {
            watchlist.map { item ->
                async {
                    semaphore.withPermit {
                        ensureActive()
                        runCatching {
                            val history = backend.getPriceHistory(item.productKey)
                            if (history.isEmpty()) return@runCatching null

                            val latest = history.first()
                            val previousPrice = item.realPrice

                            watchlistDao.updatePrice(item.productKey, latest.realPrice)

                            // 目標価格到達 / 有意な値下がりを純関数で判定。
                            // 目標到達は率に関係なく最優先で通知（ユーザーが明示的に求めた情報）。
                            // (arXiv 2509.02458: 経験的閾値で値下がり通知の頻度を制御)
                            val alert = PriceAlertEvaluator.evaluate(
                                previousPrice = previousPrice,
                                latestPrice = latest.realPrice,
                                targetPrice = item.targetPrice,
                                minDropPercent = MIN_DROP_PERCENT,
                            )
                            if (alert.shouldNotify) {
                                Drop(
                                    item = item,
                                    latest = latest.realPrice,
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

        // 目標価格到達を最優先、次に値下がり率が大きい順。最大 MAX_NOTIFICATIONS 件。
        drops.sortedWith(compareByDescending<Drop> { it.targetReached }.thenByDescending { it.pct })
            .take(MAX_NOTIFICATIONS)
            .forEach { drop ->
                priceDropCount++
                val title = if (drop.targetReached) {
                    applicationContext.getString(
                        R.string.notif_target_reached,
                        CurrencyFormatter.yen(drop.item.targetPrice ?: drop.latest),
                    )
                } else {
                    applicationContext.getString(R.string.notif_price_drop, drop.pct)
                }
                notificationManager.sendPriceAlert(
                    context = applicationContext,
                    productKey = drop.item.productKey,
                    title = title,
                    priceText = applicationContext.getString(
                        R.string.notif_price_detail,
                        drop.item.title.take(20),
                        CurrencyFormatter.yen(drop.latest),
                        CurrencyFormatter.yen(drop.prev),
                    ),
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
                                val platform = com.example.popcoon.data.model.Platform
                                    .fromId(parts.getOrNull(0))
                                val sku = parts.getOrNull(1) ?: item.productKey
                                val minProduct = com.example.popcoon.data.model.Product(
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
        try {
            val updated = watchlistDao.observeAll().first()
            WidgetUpdater.update(applicationContext, updated)
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
        return if (failureCount == watchlist.size && runAttemptCount < MAX_RETRY_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        internal const val WORK_NAME = "price_sync_daily"
        /** 1回の同期で送る通知の上限 (過剰通知防止 — arXiv PMC8523513) */
        private const val MAX_NOTIFICATIONS = 3
        /** 通知する最小値下がり率 (微小変動のノイズ通知を抑制 — arXiv 2509.02458) */
        private const val MIN_DROP_PERCENT = 3
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
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
