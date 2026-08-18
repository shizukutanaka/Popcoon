package io.github.shizukutanaka.popcoon.feature.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.shizukutanaka.popcoon.MainActivity
import io.github.shizukutanaka.popcoon.PopcoonApp
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ローカル通知マネージャ。
 *
 * Popcoon が現在発行する通知はこのクラス経由のローカル通知のみ (WorkManager の
 * PriceSyncWorker / WeeklyDigestWorker から直接呼ばれる)。Android クライアントに
 * Firebase SDK は組み込まれておらず、backend の FCM 送信ロジック (src/index.ts) は
 * デバイストークンを受け取る手段が無いため到達不能 — 「FCM のフォールバック」ではなく
 * 唯一の配信経路 (機能過不足監査で発見: 以前のコメントは FCM が実際に動いているかの
 * ように誤読させた)。
 *
 * 設計:
 *  - タップすると該当商品詳細画面に遷移 (Deep Link 経由)
 *  - 通知 ID は productKey.hashCode() で重複防止
 *  - Android 13+ の POST_NOTIFICATIONS 権限確認済みの場合のみ発行
 */
@Singleton
class LocalNotificationManager @Inject constructor() {

    companion object {
        /**
         * Context 非依存の純関数 — テストで直接呼ぶ。
         *
         * String.hashCode() (32bit) は誕生日効果で衝突しうる (通知 ID は
         * PendingIntent の request code も兼ねるため、衝突すると別商品の
         * 通知タップで違う商品詳細に飛ぶ)。UUID v3 (nameUUIDFromBytes) の
         * ハッシュ空間を使うことで衝突確率を実用上無視できる水準まで下げる。
         */
        fun notificationId(productKey: String): Int {
            val uuid = java.util.UUID.nameUUIDFromBytes(productKey.toByteArray())
            return (uuid.mostSignificantBits xor uuid.leastSignificantBits).toInt() and 0x7FFFFFFF
        }
        /** ディープリンクは [DeepLinks] を単一の真実源とする (MainActivity の解析側と一致)。 */
        fun deepLinkUri(productKey: String): String =
            io.github.shizukutanaka.popcoon.core.DeepLinks.product(productKey)
        // 価格本文はローカライズ済み notif_price_detail (string resource) で組み立てる。
        // 旧 priceAlertText は和文ハードコードかつ未使用だったため削除した。
    }

    fun sendPriceAlert(
        context: Context,
        productKey: String,
        title: String,
        priceText: String,
    ) {
        sendProductNotification(
            context = context,
            notifId = notificationId(productKey),
            productKey = productKey,
            title = title,
            body = priceText,
            failureLogMessage = { "価格アラート通知の発行に失敗: $it" },
        )
    }

    fun sendStockAlert(
        context: Context,
        productKey: String,
        productTitle: String,
    ) {
        val body = context.getString(R.string.notif_back_in_stock_body, productTitle.take(20))
        sendProductNotification(
            context = context,
            notifId = notificationId(productKey) xor 0x5A00,  // price と衝突しないオフセット
            productKey = productKey,
            title = context.getString(R.string.notif_back_in_stock),
            body = body,
            failureLogMessage = { "在庫アラート通知の発行に失敗: $it" },
        )
    }

    /**
     * 商品詳細へのディープリンクを持つ通知 (価格アラート / 在庫アラート) の共通発行処理。
     * 両者で intent flags・PendingIntent flags・NotificationCompat の組み立てが完全に
     * 重複していたため集約 (片方だけ更新して挙動が乖離するのを防ぐ)。
     */
    private fun sendProductNotification(
        context: Context,
        notifId: Int,
        productKey: String,
        title: String,
        body: String,
        failureLogMessage: (String?) -> String,
    ) {
        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(deepLinkUri(productKey))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, PopcoonApp.CHANNEL_PRICE_ALERT)
            .setSmallIcon(R.drawable.ic_shortcut_star)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }.onFailure { e ->
            // POST_NOTIFICATIONS 権限欠如等で SecurityException になり得る。
            // 握りつぶすと「アラート有効なのに通知が来ない」を診断できないため記録する。
            PopcoonLogger.w(this, failureLogMessage(e.message), e)
        }
    }

    /**
     * 1 回の同期で個別通知の上限を超えた値下がりをまとめて 1 件だけ通知する。
     *
     * 上限 (PriceSyncPlanner の maxNotifications) は割り込み回数を抑えるためのもので、
     * 情報を捨てるためのものではない。超過分を黙って落とすと、確定価格は既に DB へ
     * 書き戻されているため二度と再通知されない (ソクラテス式レビューで発見)。
     *
     * @param suppressedCount 個別通知に載らなかった値下がり件数。
     * @param titles 上記のうち先頭数件の商品名 (本文に列挙する)。
     */
    fun sendPriceDropSummary(
        context: Context,
        suppressedCount: Int,
        titles: List<String>,
    ) {
        if (suppressedCount <= 0) return
        sendWatchlistNotification(
            context = context,
            notifId = PRICE_DROP_SUMMARY_NOTIF_ID,
            channelId = PopcoonApp.CHANNEL_PRICE_ALERT,
            title = context.getString(R.string.notif_price_drop_summary, suppressedCount),
            body = titles.joinToString("\n") { it.take(30) },
            priority = NotificationCompat.PRIORITY_DEFAULT,
            failureLogMessage = { "値下がりまとめ通知の発行に失敗: $it" },
        )
    }

    fun sendWeeklyDigest(
        context: Context,
        summary: String,
    ) {
        sendWatchlistNotification(
            context = context,
            notifId = WEEKLY_DIGEST_NOTIF_ID,
            channelId = PopcoonApp.CHANNEL_WEEKLY_DIGEST,
            title = context.getString(R.string.notif_weekly_digest_title),
            body = summary,
            priority = NotificationCompat.PRIORITY_LOW,
            failureLogMessage = { "週間ダイジェスト通知の発行に失敗: $it" },
        )
    }

    /**
     * ウォッチリスト画面へのディープリンクを持つ通知 (週間ダイジェスト / 値下がりまとめ) の
     * 共通発行処理。個別商品ではなく複数商品をまとめて伝える通知はここを通す。
     *
     * 週間ダイジェストは以前 contentIntent が未設定で「タップしても何も起きない」通知だった
     * (ソクラテス式レビューで発見)。両者で同じ組み立てを重複させないよう集約する。
     */
    private fun sendWatchlistNotification(
        context: Context,
        notifId: Int,
        channelId: String,
        title: String,
        body: String,
        priority: Int,
        failureLogMessage: (String?) -> String,
    ) {
        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(io.github.shizukutanaka.popcoon.core.DeepLinks.WATCHLIST)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_shortcut_star)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(priority)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }.onFailure { e ->
            PopcoonLogger.w(this, failureLogMessage(e.message), e)
        }
    }
}

private const val WEEKLY_DIGEST_NOTIF_ID = 999
private const val PRICE_DROP_SUMMARY_NOTIF_ID = 998
