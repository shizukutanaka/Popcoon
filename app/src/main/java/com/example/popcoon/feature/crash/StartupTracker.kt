package com.example.popcoon.feature.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本番環境での cold start 計測。
 *
 * Android 15 (API 35) の ApplicationStartInfo を使用すると、
 * 詳細な startup metrics が取得できる:
 *  - cold/warm/hot 区別
 *  - 各 startup フェーズの所要時間
 *  - ローカル再現困難な performance 問題の発見
 *
 * Macrobenchmark (CI) と組み合わせて、ローカル + 本番の双方をカバー。
 *
 * プライバシー方針: 数値のみで個人情報なし。バケット集約のみ送信 (opt-in)。
 */
@Singleton
class StartupTracker @Inject constructor() {

    data class StartupMetrics(
        val totalDurationMs: Long,
        val launchedFromForegroundProcess: Boolean,
        val startType: String,  // "cold" | "warm" | "hot" | "unknown"
    )

    /**
     * Application onCreate() の早い段階で呼ぶ。
     * 直近の起動データを取得する。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun queryRecentStartup(context: Context): StartupMetrics? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null

        return runCatching {
            val infos = am.getHistoricalProcessStartReasons(1)
            val recent = infos.firstOrNull() ?: return null

            val startType = when (recent.startType) {
                android.app.ApplicationStartInfo.START_TYPE_COLD -> "cold"
                android.app.ApplicationStartInfo.START_TYPE_WARM -> "warm"
                android.app.ApplicationStartInfo.START_TYPE_HOT -> "hot"
                else -> "unknown"
            }

            val timestamps = recent.startupTimestamps
            // PROCESS_START → ACTIVITY_FIRST_DRAW までの差分
            val processStart = timestamps[
                android.app.ApplicationStartInfo.START_TIMESTAMP_LAUNCH
            ] ?: 0L
            val firstDraw = timestamps[
                android.app.ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME
            ] ?: timestamps[
                android.app.ApplicationStartInfo.START_TIMESTAMP_FULLY_DRAWN
            ] ?: 0L

            val total = if (firstDraw > processStart) firstDraw - processStart else 0L

            StartupMetrics(
                totalDurationMs = total,
                launchedFromForegroundProcess =
                    recent.reason == android.app.ApplicationStartInfo.REASON_OTHER,
                startType = startType,
            )
        }.getOrNull()
    }

    /**
     * 起動の遅延が閾値を超えたら自動で trace 起動する (オプション、要 PROFILE 権限)。
     */
    fun isStartupSlow(metrics: StartupMetrics): Boolean =
        metrics.totalDurationMs > SLOW_THRESHOLD_MS

    companion object {
        /** 業界目標: cold start 1500ms 以下 */
        const val SLOW_THRESHOLD_MS = 1500L
    }
}
