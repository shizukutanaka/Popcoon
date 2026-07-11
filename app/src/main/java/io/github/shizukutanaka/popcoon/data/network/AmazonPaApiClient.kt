package io.github.shizukutanaka.popcoon.data.network

import io.github.shizukutanaka.popcoon.BuildConfig
import io.github.shizukutanaka.popcoon.core.retryOnce
import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Amazon Product Advertising API 5.0 client for Japan marketplace.
 *
 * ⚠️ 重要 (2026-07 リサーチで判明): **PA-API 5.0 は 2026-04-30 に廃止予告され
 * 2026-05-15 に停止済み**。後継は Creators API (OAuth2 ベアラ認証、新資格情報、
 * 成果要件 10件/30日、Offers V1 は 2026-01-31 廃止)。このクライアントは旧 SigV4 +
 * webservices-fe エンドポイントを叩き続けるため、**本番では常時 4xx/5xx で失敗する**。
 *
 * 現状の緩和策 (コード変更不要・確認済み):
 *  - ProductRepository.searchWithBreaker がこの失敗をサーキットブレーカーで捕捉し、
 *    OPEN の間 Amazon ソースをスキップする (SourceOutcome.failed=true)。
 *  - 全滅でなければ Rakuten/Yahoo の結果で検索は成立する (部分成功パス)。
 *  - 商品詳細では FallbackScraper (JSON-LD) が Amazon 商品ページの実質的なデータ源になる。
 *
 * 移行 TODO (本環境では OAuth2 資格情報・成果実績が無く実施不可、要人手):
 *  - Creators API (OAuth2) クライアントへ置換。SETUP/README の PA-API 手順も更新済み。
 *  - 成果要件 (10件/30日) 未達なら Amazon ライブ価格は無効化し FallbackScraper に一本化する判断も可。
 *
 * 旧 PA-API 5.0 要件 (参考・非稼働):
 *  - Associate タグ (PartnerTag) 必須 / AWS SigV4 署名
 *  - Marketplace: www.amazon.co.jp / region: us-west-2 / host: webservices-fe.amazon.co.jp
 *  - Initial quota: 1 req/sec (8,640/日)、シェア率に応じて増加
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
        /** 署名対象 (SignedHeaders) とワイヤ上の content-type を一致させるための単一定義。 */
        private const val SIGNED_CONTENT_TYPE = "application/json; charset=utf-8"
    }

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
                "Offers.Listings.Availability.Message",
                "Offers.Listings.Availability.Type",
                "Offers.Listings.LoyaltyPoints.Points",
                "Offers.Summaries.LowestPrice",
            ),
            searchIndex = "All",
            itemCount = itemCount.coerceIn(1, 10),
        )

        val bodyJson = json.encodeToString(
            SearchItemsRequest.serializer(), request)

        val response = runCatching {
            retryOnce {
                // content-type は SignedHeaders に含まれるため、署名値と実際にワイヤに乗る値を
                // 厳密に一致させる必要がある (不一致は SignatureDoesNotMatch / 403 を招く)。
                // 単一の定数を署名と TextContent の双方に渡して取り違えを防ぐ。
                // リトライ時は signer.sign() をブロック内で再実行するため、x-amz-date も
                // 再署名時点の時刻で作り直される (古い日時のまま再送すると
                // クロックスキュー許容範囲外でサーバーに拒否されうるため、これは正しい)。
                val signed = signer.sign(
                    method = "POST",
                    path = "/paapi5/searchitems",
                    payload = bodyJson,
                    host = HOST,
                    amzTarget = AMZ_TARGET_PREFIX + "SearchItems",
                    contentType = SIGNED_CONTENT_TYPE,
                )
                val httpResp = client.post("https://$HOST/paapi5/searchitems") {
                    header("host", HOST)
                    header("content-encoding", "amz-1.0")
                    header("x-amz-date", signed.amzDate)
                    header("x-amz-target", AMZ_TARGET_PREFIX + "SearchItems")
                    header("authorization", signed.authorizationHeader)
                    setBody(TextContent(bodyJson, ContentType.parse(SIGNED_CONTENT_TYPE)))
                }
                check(httpResp.status.isSuccess()) { "PAAPI error: ${httpResp.status}" }
                httpResp.body<SearchItemsResponse>()
            }
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return emptyList()

        return response.searchResult?.items.orEmpty().mapNotNull { it.toProduct() }
    }
}

// ── Request / Response DTOs ─────────────────────────────────────────────────

@Serializable
private data class SearchItemsRequest(
    @kotlinx.serialization.SerialName("Keywords") val keywords: String,
    @kotlinx.serialization.SerialName("Marketplace") val marketplace: String,
    @kotlinx.serialization.SerialName("PartnerTag") val partnerTag: String,
    @kotlinx.serialization.SerialName("PartnerType") val partnerType: String,
    @kotlinx.serialization.SerialName("Resources") val resources: List<String>,
    @kotlinx.serialization.SerialName("SearchIndex") val searchIndex: String,
    @kotlinx.serialization.SerialName("ItemCount") val itemCount: Int,
)

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
        val listing = offers?.listings?.firstOrNull()
        val price = listing?.price?.amount?.toLong() ?: 0L
        val savingBasis = listing?.savingBasis?.amount?.toLong() ?: price
        return Product(
            sku = asin,
            title = title,
            platform = Platform.AMAZON,
            realPrice = price,
            listPrice = savingBasis,
            // LoyaltyPoints.Points が無い商品 (対象外カテゴリ・ポイント無し等) は 0。
            // 以前は resources にこのフィールドを一切要求しておらず、Amazon ポイントは
            // 全商品で恒久的に 0 表示だった (機能過不足監査で発見)。
            pointsBack = (listing?.loyaltyPoints?.points ?: 0).toLong(),
            url = detailPageUrl,
            imageUrl = images?.primary?.medium?.url,
            brand = itemInfo.byLineInfo?.brand?.displayValue,
            // PA-API Availability から在庫切れを復元 (Rakuten/Yahoo と同方針、在庫切れのみ 0)。
            stockCount = stockFromAmazonAvailability(
                listing?.availability?.type,
                listing?.availability?.message,
            ),
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
    @kotlinx.serialization.SerialName("Availability") val availability: Availability? = null,
    @kotlinx.serialization.SerialName("LoyaltyPoints") val loyaltyPoints: LoyaltyPoints? = null,
)

@Serializable
private data class Availability(
    @kotlinx.serialization.SerialName("Type") val type: String? = null,
    @kotlinx.serialization.SerialName("Message") val message: String? = null,
)

@Serializable
private data class PriceInfo(
    @kotlinx.serialization.SerialName("Amount") val amount: Double? = null,
)

// Amazon ポイント (JP マーケットプレイスのみ返る)。取得しなければ product.pointsBack が
// 恒久的に 0 のまま「ポイント還元なし」と誤表示され、PointSimulator の楽天/Yahoo!との
// 比較結果が歪んでいた (機能過不足監査で発見)。
@Serializable
private data class LoyaltyPoints(
    @kotlinx.serialization.SerialName("Points") val points: Int? = null,
)
