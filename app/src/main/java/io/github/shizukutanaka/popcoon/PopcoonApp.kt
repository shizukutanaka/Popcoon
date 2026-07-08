package io.github.shizukutanaka.popcoon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.github.shizukutanaka.popcoon.feature.crash.PrivacyCrashReporter
import io.github.shizukutanaka.popcoon.feature.settings.UserPreferences
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltAndroidApp
class PopcoonApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var crashReporter: PrivacyCrashReporter
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var prefs: UserPreferences

    // Coil3 のグローバル singleton ImageLoader を Hilt が構築した最適化版に差し替える。
    // これが無いと AsyncImage/SubcomposeAsyncImage は Coil 既定の ImageLoader
    // (メモリ RAM 25% / ディスクキャッシュ未設定) を使い、CoilImageLoaderModule の
    // チューニング (50MB メモリ + 200MB ディスク + OkHttp timeout) が一切効かなかった。
    @Inject lateinit var imageLoader: ImageLoader

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** Coil3: 全 AsyncImage 呼び出しが使う singleton ImageLoader を供給する。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
        createNotificationChannels()

        // ユーザー設定の opt-in 状態を CrashReporter に同期。
        // オプトイン時は前回セッションで永続化されたクラッシュを送信する
        // (クラッシュ時点ではプロセス終了が早くネットワーク送信が完了しないため)。
        prefs.crashReportOptin
            .onEach { enabled ->
                crashReporter.enabled = enabled
                if (enabled) runCatching { crashReporter.uploadPendingCrashes() }
                    .onFailure { PopcoonLogger.w(this@PopcoonApp, "クラッシュレポート送信失敗: ${it.message}") }
            }
            .launchIn(appScope)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // 価格アラート (高重要度)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PRICE_ALERT,
                getString(R.string.channel_price_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.channel_price_alert_desc)
                enableVibration(true)
            }
        )

        // 週刊ダイジェスト (低重要度)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WEEKLY_DIGEST,
                getString(R.string.channel_weekly_digest_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_weekly_digest_desc)
                enableVibration(false)
            }
        )

        // システム通知 (最低重要度)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYSTEM,
                getString(R.string.channel_system_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.channel_system_desc)
            }
        )
    }

    companion object {
        const val CHANNEL_PRICE_ALERT = "price_alert"
        const val CHANNEL_WEEKLY_DIGEST = "weekly_digest"
        const val CHANNEL_SYSTEM = "system"
    }
}
