package io.github.shizukutanaka.popcoon.feature.retention

import android.app.Activity
import io.github.shizukutanaka.popcoon.feature.settings.UserPreferences
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CancellationException
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
    companion object {
        const val MIN_SUCCESS_COUNT = 5
        val COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(90)

        /** Context 非依存の純関数 — テストで直接呼ぶ。 */
        fun shouldRequestNow(successCount: Int, lastReviewMs: Long, nowMs: Long): Boolean {
            if (successCount < MIN_SUCCESS_COUNT) return false
            // cooldown は包括的: ちょうど COOLDOWN_MS 経過時点ではまだ blocked (要 strictly > 90日)。
            // Google の「90日に1回」quota を厳守する保守側。ReviewPrompterLogicTest L38 の
            // 文書化済み境界仕様 (< ではなく <=) に一致させる (旧 < 実装はこの境界で誤って true を返していた)。
            if (nowMs - lastReviewMs <= COOLDOWN_MS) return false
            return true
        }
    }

    suspend fun shouldRequest(): Boolean {
        val count = prefs.successCount.first()
        val last = prefs.lastReviewRequest.first()
        return shouldRequestNow(count, last, System.currentTimeMillis())
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
        }.onFailure { if (it is CancellationException) throw it }
        // ReviewException 含めて全ての例外を握りつぶす (UX 阻害禁止)。
        // ただしコルーチンキャンセルは伝播させる。
    }

    /** 成功イベントが起きた時に呼ぶ。閾値到達したら自動で review 発火条件を満たす */
    suspend fun recordSuccess() {
        prefs.incrementSuccessCount()
    }
}
