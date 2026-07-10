package io.github.shizukutanaka.popcoon

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.github.shizukutanaka.popcoon.feature.crash.StartupTracker
import io.github.shizukutanaka.popcoon.feature.share.ShortUrlResolver
import io.github.shizukutanaka.popcoon.feature.share.UrlClassifier
import io.github.shizukutanaka.popcoon.ui.AppRootState
import io.github.shizukutanaka.popcoon.ui.AppRootViewModel
import io.github.shizukutanaka.popcoon.ui.PopcoonApp
import io.github.shizukutanaka.popcoon.worker.PriceSyncWorker
import io.github.shizukutanaka.popcoon.worker.WeeklyDigestWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    val intentEvent: StateFlow<IntentEvent> get() = _intentEvent
    private val _intentEvent = MutableStateFlow<IntentEvent>(IntentEvent.None)

    @Inject lateinit var startupTracker: StartupTracker
    @Inject lateinit var shortUrlResolver: ShortUrlResolver

    private val rootViewModel: AppRootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() は super.onCreate() より前に呼ぶ (SplashScreen API の規約)。
        // 以前は AppRootState.Loading 中 (DataStore の onboarded フラグ読込を待つ間) に
        // Compose が空の Surface を描画するだけで、起動直後が無地の空白フラッシュだった
        // (商用リリース監査で発見)。ネイティブ SplashScreen をその読込完了までそのまま
        // 表示し続けることで、フラッシュを解消する。
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { rootViewModel.state.value == AppRootState.Loading }
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
                    resolveAndEmit(url)
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

                    else -> resolveAndEmit(data)
                }
            }
        }
    }

    /**
     * UrlClassifier.classify() は既知の EC 正規ドメインパターンにのみマッチする純関数で、
     * ネットワークに触れない。ネイティブアプリの「共有」ボタンは amzn.to / a.r10.to 等の
     * 短縮URLを頻繁に出力するため、これをそのまま classify() に渡すと無条件で失敗していた
     * (機能過不足監査で発見: 共有インテントが中核体験と明記されていたにもかかわらず
     * 短縮URLは1件もテストされていなかった)。
     *
     * まず classify() を試し、失敗した場合のみ ShortUrlResolver でリダイレクトを追跡してから
     * 再度 classify() する。両方失敗したら ShareUnrecognized を発行し、以前のような
     * 無言の no-op ではなくユーザーに知らせる。
     */
    private fun resolveAndEmit(url: String) {
        UrlClassifier.classify(url)?.let { classified ->
            _intentEvent.value = IntentEvent.OpenProduct(
                productKey = "${classified.platform.id}:${classified.sku}",
                url = classified.canonicalUrl,
            )
            return
        }

        lifecycleScope.launch {
            val resolved = shortUrlResolver.resolve(url)
            val reclassified = resolved?.let { UrlClassifier.classify(it) }
            _intentEvent.value = if (reclassified != null) {
                IntentEvent.OpenProduct(
                    productKey = "${reclassified.platform.id}:${reclassified.sku}",
                    url = reclassified.canonicalUrl,
                )
            } else {
                IntentEvent.ShareUnrecognized
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
    /** 共有/リンクURLが (短縮URL解決後も) どの EC サイトの商品ページとも分類できなかった */
    data object ShareUnrecognized : IntentEvent
}
