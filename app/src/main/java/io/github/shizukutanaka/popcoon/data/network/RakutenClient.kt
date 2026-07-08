package io.github.shizukutanaka.popcoon.data.network

import io.github.shizukutanaka.popcoon.BuildConfig
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
 * 楽天市場商品検索 API (IchibaItem/Search/20220601)
 * 無償枠: 1req/sec、月間上限あり。アプリIDのみ必要。
 */
class RakutenClient(
    private val appId: String = BuildConfig.RAKUTEN_APP_ID,
) {
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 10_000
        }
    }

    suspend fun search(keyword: String, hits: Int = 30): List<Product> {
        if (appId.isBlank()) return emptyList()
        val resp = runCatching {
            val httpResp = client.get("https://app.rakuten.co.jp/services/api/IchibaItem/Search/20220601") {
                parameter("format", "json")
                parameter("applicationId", appId)
                parameter("keyword", keyword)
                parameter("hits", hits.coerceIn(1, 30))
            }
            check(httpResp.status.isSuccess()) { "Rakuten API error: ${httpResp.status}" }
            httpResp.body<RakutenResponse>()
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return emptyList()

        // DTO → Product の変換は純粋関数 (RakutenMapper.kt) に集約。
        return resp.Items.map { it.Item.toProduct() }
    }
}
