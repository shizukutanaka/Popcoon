package io.github.shizukutanaka.popcoon.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.github.shizukutanaka.popcoon.data.db.WatchlistDao
import io.github.shizukutanaka.popcoon.feature.notification.LocalNotificationManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 週次ダイジェスト Worker。
 *
 * ウォッチリストに追加した商品のうち「追加時より値下がりしている件数」を集計し、
 * ローカル通知でサマリーを届ける。ネットワーク不要（端末内データのみ）。
 *
 * スケジュール: 7日ごと (WorkManager の柔軟窓で月曜朝に近い時刻に発火)。
 * ウォッチリストが空のとき、および値下がりが 1 件も無いときは静かに終了（ノイズ通知を抑制）。
 *
 * addedPrice == 0 の商品は v3 以前に登録された基準なしアイテムとして除外する。
 * realPrice <= 0 の商品も取得失敗の汚染レコードとして除外する。
 */
@HiltWorker
class WeeklyDigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val watchlistDao: WatchlistDao,
    private val notificationManager: LocalNotificationManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val watchlist = try {
            watchlistDao.observeAll().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PopcoonLogger.w(this, "週次ダイジェスト: ウォッチリスト取得失敗 ${e.message}")
            return Result.success()
        }

        if (watchlist.isEmpty()) return Result.success()

        val totalCount = watchlist.size
        val dropCount = dropCountFrom(watchlist.map { it.realPrice to it.addedPrice })

        // 値下がりが 1 件も無い週は送らない。本文は「N件中0件が値下がり中」となり
        // 情報量ゼロの週次割り込みにしかならず、本クラスが空ウォッチリストで既に
        // 掲げている「ノイズ通知を抑制」という方針と矛盾していた。
        if (dropCount == 0) return Result.success()

        val summary = applicationContext.getString(
            R.string.notif_weekly_digest_body,
            totalCount,
            dropCount,
        )

        notificationManager.sendWeeklyDigest(applicationContext, summary)
        return Result.success()
    }

    companion object {
        internal const val WORK_NAME = "weekly_digest"

        /**
         * 追加時価格より現在価格が低い商品の件数を返す純関数。
         *
         * 除外するもの:
         *  - addedPrice == 0 — v3 以前に登録された基準なしアイテム。
         *  - realPrice <= 0 — 取得失敗を 0 として記録してしまった汚染レコード。
         *    `realPrice < addedPrice` だけで判定すると 0 円は常に「値下がり」になり、
         *    ダイジェストの件数が実態より水増しされる (BuyTimingScorer と同じ ¥0 汚染。
         *    書き込み側は塞いだが既存 DB の行は残りうるため、読み出し側でも無視する)。
         */
        fun dropCountFrom(pricesPairs: List<Pair<Long, Long>>): Int =
            pricesPairs.count { (realPrice, addedPrice) ->
                addedPrice > 0 && realPrice > 0 && realPrice < addedPrice
            }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeeklyDigestWorker>(
                repeatInterval = 7,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
                flexTimeInterval = 12,
                flexTimeIntervalUnit = TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build()
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
