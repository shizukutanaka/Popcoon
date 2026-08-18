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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import io.github.shizukutanaka.popcoon.core.PopcoonLogger

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
        // backend に複数件をまとめて送る batch エンドポイントは無い (POST /v1/history は
        // 1 リクエスト 1 PriceRecord)。そのため件数分の HTTP リクエストは避けられないが、
        // 完全に順次だと数十件で数秒かかる。Semaphore で上限を設けて並行化する
        // (PriceSyncWorker/SearchViewModel と同じ有界並行パターン、無制限 fan-out には戻さない)。
        private const val MAX_CONCURRENCY = 8
    }

    /**
     * 価格履歴をまとめて fire-and-forget で送信。失敗しても UI に影響させない。
     *
     * 1 検索の全結果 (数十件) を Semaphore(MAX_CONCURRENCY) で有界並行送信する。
     * 以前は結果 1 件ごとに launch しており、検索のたびに数十の無制限な並行 POST が
     * never-cancelled な singleton scope 上に積まれていた (fan-out) — その後 1 コルーチン内の
     * 完全順次に修正されたが、数十件では数秒かかり遅かった。有界並行で両者の問題を避ける。
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
                val succeeded = AtomicInteger(0)
                val failed = AtomicInteger(0)
                val semaphore = Semaphore(MAX_CONCURRENCY)
                coroutineScope {
                    records.map { record ->
                        async {
                            semaphore.withPermit {
                                val success = retryWithBackoff(maxAttempts = 3) {
                                    client.post("$baseUrl/v1/history") {
                                        contentType(ContentType.Application.Json)
                                        setBody(record)
                                    }.bodyAsText()
                                }
                                if (success) succeeded.incrementAndGet() else failed.incrementAndGet()
                            }
                        }
                    }.awaitAll()
                }
                if (failed.get() > 0) {
                    PopcoonLogger.w(
                        this@BackendClient,
                        "Price sync: ${succeeded.get()}/${records.size} succeeded, ${failed.get()} failed",
                    )
                }
            } ?: PopcoonLogger.w(this@BackendClient, "Price sync aborted: exceeded ${BATCH_TIMEOUT_MS}ms budget")
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
                    PopcoonLogger.w(
                        this@BackendClient,
                        "Price POST failed after $maxAttempts attempts: ${e.message}",
                    )
                }
            }
        }
        return false
    }

    /**
     * 価格履歴を取得する。**アプリ内で `List<PriceRecord>` が生まれる唯一の場所**であり、
     * ここが `realPrice > 0` の単一の関門になる。
     *
     * `realPrice <= 0` は取得失敗を 0 円として記録した汚染レコードで、実際に成立した
     * 価格ではない。書き込み側 (FallbackScraper の捏造停止 cdf61dc、backend の
     * `real_price <= 0` 拒否 5c0ade0) は塞いだが、それ以前に蓄積した行は残る。
     * 下流 (予測エンジン・買い時スコア・グラフ・ダークパターン検出・CSV 出力) は
     * それぞれ自前のガードも持つが、**入口で 1 回落とすのが本筋**で、
     * 個別ガードは多重防御として残す。
     */
    suspend fun getPriceHistory(productKey: String): List<PriceRecord> {
        return runCatching {
            val raw = client.get("$baseUrl/v1/history") {
                parameter("key", productKey)
            }.body<HistoryResponse>().records
            val valid = raw.filter { it.realPrice > 0 }
            if (valid.size != raw.size) {
                // 黙って捨てない — 汚染がどれだけ残っているかを診断できるようにする
                // (productKey は記録しない: PopcoonLogger の PII 方針)。
                PopcoonLogger.w(this, "価格履歴の無効レコードを除外: ${raw.size - valid.size}/${raw.size} 件")
            }
            valid
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
