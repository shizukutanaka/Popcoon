package com.example.popcoon.feature.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.popcoon.MainActivity
import com.example.popcoon.PopcoonApp
import com.example.popcoon.R
import com.example.popcoon.core.PopcoonLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ローカル通知マネージャ。
 *
 * FCM が届かない場合のフォールバックとして、
 * Worker からローカル通知を直接発行する。
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
            com.example.popcoon.core.DeepLinks.product(productKey)
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

    fun sendWeeklyDigest(
        context: Context,
        summary: String,
    ) {
        // 価格/在庫アラートと異なり、従来 contentIntent が未設定で「タップしても何も起きない」
        // 通知になっていた (ソクラテス式レビューで発見)。ウォッチリスト画面への Deep Link を張る。
        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(com.example.popcoon.core.DeepLinks.WATCHLIST)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            WEEKLY_DIGEST_NOTIF_ID,
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, PopcoonApp.CHANNEL_WEEKLY_DIGEST)
            .setSmallIcon(R.drawable.ic_shortcut_star)
            .setContentTitle(context.getString(R.string.notif_weekly_digest_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(WEEKLY_DIGEST_NOTIF_ID, notification)
        }.onFailure { e ->
            PopcoonLogger.w(this, "週間ダイジェスト通知の発行に失敗: ${e.message}", e)
        }
    }
}

private const val WEEKLY_DIGEST_NOTIF_ID = 999
