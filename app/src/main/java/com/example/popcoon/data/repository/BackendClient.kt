package com.example.popcoon.data.repository

import com.example.popcoon.BuildConfig
import com.example.popcoon.data.model.PriceRecord
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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
    }

    private val baseUrl = BuildConfig.BACKEND_URL
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 価格を fire-and-forget で送信。
     * 失敗しても UI に影響させない。
     */
    fun postPriceAsync(record: PriceRecord) {
        asyncScope.launch {
            runCatching {
                client.post("$baseUrl/v1/history") {
                    contentType(ContentType.Application.Json)
                    setBody(record)
                }
            }
        }
    }

    suspend fun getPriceHistory(productKey: String): List<PriceRecord> {
        return runCatching {
            client.get("$baseUrl/v1/history") {
                parameter("key", productKey)
            }.body<HistoryResponse>().records
        }.getOrDefault(emptyList())
    }

    /**
     * GDPR Article 17: ユーザーが「全削除」を押したら呼ぶ。
     * 端末内データの削除は呼び出し側で別途実施。
     */
    suspend fun deleteAllData(deviceToken: String): Boolean {
        return runCatching {
            client.delete("$baseUrl/v1/device") {
                header("x-device-token", deviceToken)
            }
        }.isSuccess
    }

    @Serializable
    private data class HistoryResponse(
        val product_key: String,
        val count: Int,
        val records: List<PriceRecord>,
    )
}
