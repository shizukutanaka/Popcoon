package com.example.popcoon.data.network

import com.example.popcoon.BuildConfig
import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Amazon Product Advertising API 5.0 client for Japan marketplace.
 *
 * 要件:
 *  - Associate タグ (PartnerTag) 必須
 *  - AWS SigV4 署名
 *  - Marketplace: www.amazon.co.jp
 *  - region: us-west-2
 *  - host: webservices-fe.amazon.co.jp
 *
 * 制限:
 *  - Initial quota: 1 req/sec (8,640/日)
 *  - シェア率に応じて増加
 */
class AmazonPaApiClient(
    private val accessKey: String = BuildConfig.AMAZON_ACCESS_KEY,
    private val secretKey: String = BuildConfig.AMAZON_SECRET_KEY,
    private val partnerTag: String = BuildConfig.AMAZON_PARTNER_TAG,
) {
    companion object {
        private const val HOST = "webservices-fe.amazon.co.jp"
        private const val REGION = "us-west-2"
        private const val SERVICE = "ProductAdvertisingAPI"
        private const val MARKETPLACE = "www.amazon.co.jp"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val AMZ_TARGET_PREFIX =
            "com.amazon.paapi5.v1.ProductAdvertisingAPIv1."
    }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // SigV4 署名を AwsSigV4Signer に委譲 (300行→200行に削減)
    private val signer = AwsSigV4Signer(accessKey, secretKey, REGION, SERVICE)

    suspend fun searchItems(keyword: String, itemCount: Int = 10): List<Product> {
        if (accessKey.isBlank() || secretKey.isBlank()) return emptyList()

        val request = SearchItemsRequest(
            keywords = keyword,
            marketplace = MARKETPLACE,
            partnerTag = partnerTag,
            partnerType = "Associates",
            resources = listOf(
                "ItemInfo.Title",
                "ItemInfo.ByLineInfo",
                "Images.Primary.Medium",
                "Offers.Listings.Price",
                "Offers.Listings.SavingBasis",
                "Offers.Summaries.LowestPrice",
            ),
            searchIndex = "All",
            itemCount = itemCount.coerceIn(1, 10),
        )

        val bodyJson = json.encodeToString(
            SearchItemsRequest.serializer(), request)

        val response = runCatching {
            val signed = signer.sign(
                method = "POST",
                path = "/paapi5/searchitems",
                payload = bodyJson,
                host = HOST,
                amzTarget = AMZ_TARGET_PREFIX + "SearchItems",
            )
            client.post("https://$HOST/paapi5/searchitems") {
                header("host", HOST)
                header("content-encoding", "amz-1.0")
                header("x-amz-date", signed.amzDate)
                header("x-amz-target", AMZ_TARGET_PREFIX + "SearchItems")
                header("authorization", signed.authorizationHeader)
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }.body<SearchItemsResponse>()
        }.getOrNull() ?: return emptyList()

        return response.searchResult?.items.orEmpty().mapNotNull { it.toProduct() }
    }
}

// ── Request / Response DTOs ─────────────────────────────────────────────────

@Serializable
private data class SearchItemsRequest(
    val Keywords: String,
    val Marketplace: String,
    val PartnerTag: String,
    val PartnerType: String,
    val Resources: List<String>,
    val SearchIndex: String,
    val ItemCount: Int,
) {
    constructor(
        keywords: String, marketplace: String, partnerTag: String,
        partnerType: String, resources: List<String>, searchIndex: String,
        itemCount: Int,
    ) : this(
        Keywords = keywords, Marketplace = marketplace, PartnerTag = partnerTag,
        PartnerType = partnerType, Resources = resources, SearchIndex = searchIndex,
        ItemCount = itemCount,
    )
}

@Serializable
private data class SearchItemsResponse(
    @kotlinx.serialization.SerialName("SearchResult")
    val searchResult: SearchResult? = null,
)

@Serializable
private data class SearchResult(
    @kotlinx.serialization.SerialName("Items") val items: List<PaItem>? = null,
)

@Serializable
private data class PaItem(
    @kotlinx.serialization.SerialName("ASIN") val asin: String,
    @kotlinx.serialization.SerialName("DetailPageURL") val detailPageUrl: String,
    @kotlinx.serialization.SerialName("ItemInfo") val itemInfo: ItemInfo? = null,
    @kotlinx.serialization.SerialName("Images") val images: Images? = null,
    @kotlinx.serialization.SerialName("Offers") val offers: Offers? = null,
) {
    fun toProduct(): Product? {
        val title = itemInfo?.title?.displayValue ?: return null
        val price = offers?.listings?.firstOrNull()?.price?.amount?.toLong() ?: 0L
        val savingBasis = offers?.listings?.firstOrNull()?.savingBasis?.amount?.toLong() ?: price
        return Product(
            sku = asin,
            title = title,
            platform = Platform.AMAZON,
            realPrice = price,
            listPrice = savingBasis,
            url = detailPageUrl,
            imageUrl = images?.primary?.medium?.url,
            brand = itemInfo.byLineInfo?.brand?.displayValue,
        )
    }
}

@Serializable
private data class ItemInfo(
    @kotlinx.serialization.SerialName("Title") val title: DisplayValue? = null,
    @kotlinx.serialization.SerialName("ByLineInfo") val byLineInfo: ByLineInfo? = null,
)

@Serializable
private data class ByLineInfo(
    @kotlinx.serialization.SerialName("Brand") val brand: DisplayValue? = null,
)

@Serializable
private data class DisplayValue(
    @kotlinx.serialization.SerialName("DisplayValue") val displayValue: String? = null,
)

@Serializable
private data class Images(
    @kotlinx.serialization.SerialName("Primary") val primary: Primary? = null,
)

@Serializable
private data class Primary(
    @kotlinx.serialization.SerialName("Medium") val medium: ImageData? = null,
)

@Serializable
private data class ImageData(
    @kotlinx.serialization.SerialName("URL") val url: String? = null,
)

@Serializable
private data class Offers(
    @kotlinx.serialization.SerialName("Listings") val listings: List<Listing>? = null,
)

@Serializable
private data class Listing(
    @kotlinx.serialization.SerialName("Price") val price: PriceInfo? = null,
    @kotlinx.serialization.SerialName("SavingBasis") val savingBasis: PriceInfo? = null,
)

@Serializable
private data class PriceInfo(
    @kotlinx.serialization.SerialName("Amount") val amount: Double? = null,
)
