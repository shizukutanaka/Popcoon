package com.example.popcoon.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.popcoon.core.PopcoonLogger
import com.example.popcoon.data.db.WatchlistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ウィジェット更新マネージャ。
 *
 * 改善点:
 *  - SharedPreferences は `apply()` を使う (非同期書き込み — UI スレッドブロックなし)
 *  - 連続呼び出しは 500ms デバウンス (ウォッチリスト一括追加時の updateAll() 連発防止)
 *  - 構造化ロガー使用
 */
object WidgetUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingJob: Job? = null
    private val DEBOUNCE_MS = 500L
    // pendingJob は Main (VM) と Worker スレッドの双方から触るため、
    // check-then-act (cancel→再代入) を lock で atomic にする。
    private val lock = Any()

    /**
     * 即時更新が必要な場合に呼ぶ (例: ユーザーが ★ ボタンを押した瞬間)。
     * 連続呼び出しは末尾の値だけが反映される (デバウンス)。
     */
    fun update(context: Context, items: List<WatchlistItem>) {
        synchronized(lock) {
            pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(DEBOUNCE_MS)
                applyUpdate(context, items)
            }
        }
    }

    /** デバウンスなしで即実行 (Worker などからの呼び出し用) */
    suspend fun updateImmediate(context: Context, items: List<WatchlistItem>) {
        synchronized(lock) { pendingJob?.cancel() }
        applyUpdate(context, items)
    }

    private suspend fun applyUpdate(context: Context, items: List<WatchlistItem>) {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("widget_cache", Context.MODE_PRIVATE)
            // apply() — UI スレッドブロックなし、非同期書き込み
            prefs.edit().apply {
                val topItems = items.take(3)
                putInt("count", topItems.size)
                topItems.forEachIndexed { i, item ->
                    putString("title_$i", item.title)
                    putLong("price_$i", item.realPrice)
                    putString("verdict_$i", "NEUTRAL")
                }
                apply()
            }
        }

        runCatching {
            PopcoonWidget().updateAll(context)
            PopcoonLogger.d("WidgetUpdater", "ウィジェット更新完了 (${items.size} 件)")
        }.onFailure { e ->
            PopcoonLogger.w("WidgetUpdater", "ウィジェット更新失敗", e)
        }
    }
}
