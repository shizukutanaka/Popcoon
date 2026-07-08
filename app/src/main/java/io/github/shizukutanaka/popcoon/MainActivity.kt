package io.github.shizukutanaka.popcoon

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.github.shizukutanaka.popcoon.feature.crash.StartupTracker
import io.github.shizukutanaka.popcoon.feature.share.UrlClassifier
import io.github.shizukutanaka.popcoon.ui.PopcoonApp
import io.github.shizukutanaka.popcoon.worker.PriceSyncWorker
import io.github.shizukutanaka.popcoon.worker.WeeklyDigestWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    val intentEvent: StateFlow<IntentEvent> get() = _intentEvent
    private val _intentEvent = MutableStateFlow<IntentEvent>(IntentEvent.None)

    @Inject lateinit var startupTracker: StartupTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        // バックグラウンド価格同期を日次スケジュール
        PriceSyncWorker.schedule(applicationContext)
        // ウォッチリスト週次ダイジェスト通知をスケジュール
        WeeklyDigestWorker.schedule(applicationContext)
        // 前回起動のパフォーマンスを計測 (API 35+、遅延起動を検知してログ)
        logStartupPerformance()

        setContent {
            PopcoonApp(initialEvent = intentEvent)
        }
    }

    /**
     * 前回の cold/warm/hot 起動メトリクスを取得し、遅い起動を WARN ログに残す。
     * API 35 (ApplicationStartInfo) 未満では何もしない。本番のみで再現する遅延を
     * ローカルログに残し、Macrobenchmark (CI) と相補的に startup 回帰を可視化する。
     */
    private fun logStartupPerformance() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val metrics = startupTracker.queryRecentStartup(applicationContext) ?: return
        if (startupTracker.isStartupSlow(metrics)) {
            PopcoonLogger.w(
                this,
                "遅い起動を検知: ${metrics.totalDurationMs}ms (${metrics.startType})",
            )
        } else {
            PopcoonLogger.i(
                this,
                "起動 ${metrics.totalDurationMs}ms (${metrics.startType})",
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    val url = UrlClassifier.extractUrl(text) ?: return
                    val classified = UrlClassifier.classify(url) ?: return
                    _intentEvent.value = IntentEvent.OpenProduct(
                        productKey = "${classified.platform.id}:${classified.sku}",
                        url = classified.canonicalUrl,
                    )
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data?.toString() ?: return
                val productKey = io.github.shizukutanaka.popcoon.core.DeepLinks.productKeyOrNull(data)
                when {
                    io.github.shizukutanaka.popcoon.core.DeepLinks.isBarcode(data) ->
                        _intentEvent.value = IntentEvent.OpenBarcode

                    io.github.shizukutanaka.popcoon.core.DeepLinks.isWatchlist(data) ->
                        _intentEvent.value = IntentEvent.OpenWatchlist

                    io.github.shizukutanaka.popcoon.core.DeepLinks.isSearch(data) -> {
                        val query = intent.data?.getQueryParameter("q").orEmpty()
                        _intentEvent.value = IntentEvent.StartSearch(query)
                    }

                    productKey != null -> {
                        _intentEvent.value = IntentEvent.OpenProduct(productKey = productKey, url = "")
                    }

                    else -> {
                        val classified = UrlClassifier.classify(data) ?: return
                        _intentEvent.value = IntentEvent.OpenProduct(
                            productKey = "${classified.platform.id}:${classified.sku}",
                            url = classified.canonicalUrl,
                        )
                    }
                }
            }
        }
    }
}

sealed interface IntentEvent {
    data object None : IntentEvent
    data class OpenProduct(val productKey: String, val url: String) : IntentEvent
    data class StartSearch(val query: String) : IntentEvent
    data object OpenBarcode : IntentEvent
    data object OpenWatchlist : IntentEvent
}
