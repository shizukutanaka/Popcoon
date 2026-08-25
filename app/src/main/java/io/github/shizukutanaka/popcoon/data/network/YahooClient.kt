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
        // **失敗は握り潰さず呼び出し元へ伝える**。以前はここで runCatching + getOrNull により
        // 例外を emptyList() に変換していたため、呼び出し元の
        // ProductRepository.searchWithBreaker から見ると「API 成功・0 件」と区別が付かず、
        //  (a) recordSuccess() が呼ばれ **CircuitBreaker が永久に開かない**
        //      (連続障害中の無駄なリクエストを止めるという存在理由そのものが機能しない)
        //  (b) SourceOutcome.failed が常に false になり「全滅」判定が働かず、
        //      通信障害と「該当商品なし」を UI が区別できない
        // という 2 つの機能が丸ごと死んでいた。searchWithBreaker は例外を捕捉して
        // 記録・フォールバックする実装になっているので、ここは素通しでよい。
        // retryOnce は CancellationException を再 throw し、2 回目の失敗もそのまま伝播する。
        val resp = retryOnce {
            val httpResp = client.get("https://shopping.yahooapis.jp/ShoppingWebService/V3/itemSearch") {
                parameter("appid", appId)
                parameter("query", keyword)
                parameter("results", results.coerceIn(1, 50))
            }
            check(httpResp.status.isSuccess()) { "Yahoo API error: ${httpResp.status}" }
            httpResp.body<YahooResponse>()
        }

        // DTO → Product の変換は純粋関数 (YahooMapper.kt) に集約。
        // price <= 0 は RakutenClient と同じ理由で入口から除外する。
        return resp.hits.filter { it.price > 0 }.map { it.toProduct() }
    }
}
