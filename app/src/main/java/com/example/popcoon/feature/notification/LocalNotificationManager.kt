package com.example.popcoon.feature.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.popcoon.MainActivity
import com.example.popcoon.PopcoonApp
import com.example.popcoon.R
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
        /** Context 非依存の純関数 — テストで直接呼ぶ。 */
        fun notificationId(productKey: String): Int = productKey.hashCode()
        fun deepLinkUri(productKey: String): String = "popcoon://product/$productKey"
        fun priceAlertText(currentPrice: Long, previousPrice: Long): String =
            "¥${"%,d".format(currentPrice)} (前回: ¥${"%,d".format(previousPrice)})"
    }

    fun sendPriceAlert(
        context: Context,
        productKey: String,
        title: String,
        priceText: String,
    ) {
        val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("popcoon://product/$productKey")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            productKey.hashCode(),
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, PopcoonApp.CHANNEL_PRICE_ALERT)
            .setSmallIcon(R.drawable.ic_shortcut_star)
            .setContentTitle(title)
            .setContentText(priceText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(priceText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(productKey.hashCode(), notification)
        }
    }

    fun sendWeeklyDigest(
        context: Context,
        summary: String,
    ) {
        val notification = NotificationCompat.Builder(context, PopcoonApp.CHANNEL_WEEKLY_DIGEST)
            .setSmallIcon(R.drawable.ic_shortcut_star)
            .setContentTitle("週間価格まとめ")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(999, notification)
        }
    }
}
