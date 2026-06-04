package com.example.popcoon.feature.retention

import android.app.Activity
import com.example.popcoon.feature.settings.UserPreferences
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

/**
 * Google Play In-App Review トリガー。
 *
 * 業界推奨パターン:
 *  - 成功イベントの後に呼ぶ (購入完了、価格通知でお得情報受取、買い時判定で BUY_NOW 確認)
 *  - 起動直後・操作中・エラー直後には呼ばない
 *  - 同じユーザーには 90日に1回まで (Google 側の quota もこれ)
 *  - 5回目の成功イベント以降に発火
 *
 * Popcoon の成功イベント定義:
 *  - 商品詳細を開いて 10秒以上滞在
 *  - watchlist に追加
 *  - dark pattern 警告で「待ち」を選択
 *  - AI advice に「役立った」フィードバック
 */
@Singleton
class ReviewPrompter @Inject constructor(
    private val prefs: UserPreferences,
) {
    private val minSuccessCount = 5
    private val reCooldownMs = TimeUnit.DAYS.toMillis(90)

    suspend fun shouldRequest(): Boolean {
        val count = prefs.successCount.first()
        if (count < minSuccessCount) return false
        val last = prefs.lastReviewRequest.first()
        if (System.currentTimeMillis() - last < reCooldownMs) return false
        return true
    }

    /**
     * Activity から呼び出す。
     * Play サービス未インストール環境では noop。
     */
    suspend fun requestIfEligible(activity: Activity) {
        if (!shouldRequest()) return

        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(activity, reviewInfo)
                    flow.addOnCompleteListener {
                        // 成功失敗に関わらず最終リクエスト時刻を記録
                        // (cooldown を尊重)
                    }
                }
            }
            prefs.markReviewRequested()
        }
        // ReviewException 含めて全ての例外を握りつぶす (UX 阻害禁止)
    }

    /** 成功イベントが起きた時に呼ぶ。閾値到達したら自動で review 発火条件を満たす */
    suspend fun recordSuccess() {
        prefs.incrementSuccessCount()
    }
}
