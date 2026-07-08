package io.github.shizukutanaka.popcoon.data.network

import io.github.shizukutanaka.popcoon.BuildConfig
import io.github.shizukutanaka.popcoon.core.retryOnce
import io.github.shizukutanaka.popcoon.data.model.Product
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Yahoo!ショッピング商品検索API (v3/itemSearch)
 * 無償枠: App ID のみ必要、レート制限あり。
 */
class YahooClient(
    private val appId: String = BuildConfig.YAHOO_APP_ID,
) {
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 10_000
        }
    }

    suspend fun search(keyword: String, results: Int = 30): List<Product> {
        if (appId.isBlank()) return emptyList()
        val resp = runCatching {
            retryOnce {
                val httpResp = client.get("https://shopping.yahooapis.jp/ShoppingWebService/V3/itemSearch") {
                    parameter("appid", appId)
                    parameter("query", keyword)
                    parameter("results", results.coerceIn(1, 50))
                }
                check(httpResp.status.isSuccess()) { "Yahoo API error: ${httpResp.status}" }
                httpResp.body<YahooResponse>()
            }
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return emptyList()

        // DTO → Product の変換は純粋関数 (YahooMapper.kt) に集約。
        return resp.hits.map { it.toProduct() }
    }
}
