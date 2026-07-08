package io.github.shizukutanaka.popcoon.data.repository

import io.github.shizukutanaka.popcoon.BuildConfig
import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Cloudflare Workers backend クライアント。
 * 価格履歴の共有プール、アラート登録、GDPR 削除エンドポイント。
 */
@Singleton
class BackendClient @Inject constructor() {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 10_000
        }
    }

    private val baseUrl = BuildConfig.BACKEND_URL
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val BATCH_TIMEOUT_MS = 120_000L  // 2分: 大量 records でも無制限に走らせない
    }

    /**
     * 価格履歴をまとめて fire-and-forget で送信。失敗しても UI に影響させない。
     *
     * 1 検索の全結果 (数十件) を **1 つのコルーチン内で順次** 送る。
     * 以前は結果 1 件ごとに launch しており、検索のたびに数十の無制限な並行 POST が
     * never-cancelled な singleton scope 上に積まれていた (fan-out)。
     * レスポンスは bodyAsText() で消費してコネクションを解放する。
     *
     * 再試行: 指数バックオフで最大 3 回まで試行。
     *
     * asyncScope は singleton (アプリ全体で 1 つ、never-cancelled) のため、
     * 万一 records が大量かつ全件リトライ尽くしになっても battery/network を
     * 無制限に消費しないよう、バッチ全体に上限時間を設ける。
     */
    fun postPricesAsync(records: List<PriceRecord>) {
        if (records.isEmpty()) return
        asyncScope.launch {
            withTimeoutOrNull(BATCH_TIMEOUT_MS) {
                var succeeded = 0
                var failed = 0
                records.forEach { record ->
                    val success = retryWithBackoff(maxAttempts = 3) {
                        client.post("$baseUrl/v1/history") {
                            contentType(ContentType.Application.Json)
                            setBody(record)
                        }.bodyAsText()
                    }
                    if (success) succeeded++ else failed++
                }
                if (failed > 0) {
                    Log.w(
                        "BackendClient",
                        "Price sync: $succeeded/${records.size} succeeded, $failed failed",
                    )
                }
            } ?: Log.w("BackendClient", "Price sync aborted: exceeded ${BATCH_TIMEOUT_MS}ms budget")
        }
    }

    private suspend fun retryWithBackoff(maxAttempts: Int, block: suspend () -> Unit): Boolean {
        repeat(maxAttempts) { attempt ->
            runCatching {
                block()
                return true
            }.onFailure { e ->
                if (e is CancellationException) throw e
                if (attempt < maxAttempts - 1) {
                    val delayMs = (1000 * (1 shl attempt)).toLong()  // 1s, 2s, 4s
                    delay(delayMs)
                } else {
                    Log.w(
                        "BackendClient",
                        "Price POST failed after $maxAttempts attempts: ${e.message}",
                    )
                }
            }
        }
        return false
    }

    suspend fun getPriceHistory(productKey: String): List<PriceRecord> {
        return runCatching {
            client.get("$baseUrl/v1/history") {
                parameter("key", productKey)
            }.body<HistoryResponse>().records
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    /**
     * GDPR Article 17: 端末内データ削除のみで完結する設計のため、
     * このクラスに「サーバー側削除」エンドポイントは存在しない。
     * (Tier 56 参照: アプリはデバイス識別子を一切持たないため削除対象ゼロ)
     */

    @Serializable
    private data class HistoryResponse(
        @kotlinx.serialization.SerialName("product_key") val productKey: String,
        val count: Int,
        val records: List<PriceRecord>,
    )
}
