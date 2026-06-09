package com.example.popcoon.data.network

import com.example.popcoon.BuildConfig
import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
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
            client.get("https://shopping.yahooapis.jp/ShoppingWebService/V3/itemSearch") {
                parameter("appid", appId)
                parameter("query", keyword)
                parameter("results", results.coerceIn(1, 50))
            }.body<YahooResponse>()
        }.getOrNull() ?: return emptyList()

        return resp.hits.map { hit ->
            val price = hit.price
            // defaultPrice = 通常価格（参考価格）。premiumPrice はプレミアム会員向けの
            // 割引価格で realPrice より低くなり得るため list price には使わない。
            val listPrice = hit.priceLabel?.defaultPrice ?: price
            val shipping = hit.shipping?.takeIf { it.code == 2 }?.let { 0L } ?: 500L  // 近似
            Product(
                sku = hit.code,
                title = hit.name,
                platform = Platform.YAHOO,
                realPrice = price.toLong(),
                listPrice = listPrice.toLong(),
                shippingFee = shipping,
                pointsBack = 0L,
                url = hit.url,
                rating = hit.review?.rate,
                reviewCount = hit.review?.count ?: 0,
                imageUrl = hit.image?.medium,
                brand = hit.brand?.name,
            )
        }
    }
}

@Serializable
private data class YahooResponse(val hits: List<Hit>) {
    @Serializable
    data class Hit(
        val code: String,
        val name: String,
        val price: Int,
        val url: String,
        val priceLabel: PriceLabel? = null,
        val review: Review? = null,
        val image: Image? = null,
        val brand: Brand? = null,
        val shipping: Shipping? = null,
    )
    @Serializable data class PriceLabel(val defaultPrice: Int? = null, val premiumPrice: Int? = null)
    @Serializable data class Review(val rate: Float, val count: Int)
    @Serializable data class Image(val medium: String)
    @Serializable data class Brand(val name: String)
    @Serializable data class Shipping(val code: Int)
}
