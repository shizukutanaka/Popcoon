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
            client.get("https://app.rakuten.co.jp/services/api/IchibaItem/Search/20220601") {
                parameter("format", "json")
                parameter("applicationId", appId)
                parameter("keyword", keyword)
                parameter("hits", hits.coerceIn(1, 30))
            }.body<RakutenResponse>()
        }.getOrNull() ?: return emptyList()

        return resp.Items.map { it.Item }.map { i ->
            Product(
                sku = i.itemCode,
                title = i.itemName,
                platform = Platform.RAKUTEN,
                realPrice = i.itemPrice.toLong(),
                listPrice = i.itemPrice.toLong(),  // 楽天は定価返さない
                shippingFee = 0L,
                pointsBack = 0L,
                url = i.itemUrl,
                rating = i.reviewAverage?.toFloat(),
                reviewCount = i.reviewCount ?: 0,
                imageUrl = i.mediumImageUrls.firstOrNull()?.imageUrl,
                brand = i.shopName,
            )
        }
    }
}

@Serializable
private data class RakutenResponse(val Items: List<ItemContainer>) {
    @Serializable
    data class ItemContainer(val Item: RakutenItem)

    @Serializable
    data class RakutenItem(
        val itemCode: String,
        val itemName: String,
        val itemPrice: Int,
        val itemUrl: String,
        val shopName: String,
        val reviewAverage: Double? = null,
        val reviewCount: Int? = null,
        val mediumImageUrls: List<ImageUrl> = emptyList(),
    )

    @Serializable
    data class ImageUrl(val imageUrl: String)
}
