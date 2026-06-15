package com.example.popcoon

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.popcoon.feature.share.UrlClassifier
import com.example.popcoon.ui.PopcoonApp
import com.example.popcoon.worker.PriceSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    val intentEvent: StateFlow<IntentEvent> get() = _intentEvent
    private val _intentEvent = MutableStateFlow<IntentEvent>(IntentEvent.None)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        // バックグラウンド価格同期を日次スケジュール
        PriceSyncWorker.schedule(applicationContext)

        setContent {
            PopcoonApp(initialEvent = intentEvent)
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
                val productKey = com.example.popcoon.core.DeepLinks.productKeyOrNull(data)
                when {
                    com.example.popcoon.core.DeepLinks.isBarcode(data) ->
                        _intentEvent.value = IntentEvent.OpenBarcode

                    com.example.popcoon.core.DeepLinks.isWatchlist(data) ->
                        _intentEvent.value = IntentEvent.OpenWatchlist

                    com.example.popcoon.core.DeepLinks.isSearch(data) -> {
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
