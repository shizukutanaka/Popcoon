package io.github.shizukutanaka.popcoon.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * kotlinx.serialization は java.time.Instant の組み込みシリアライザを持たないため、
 * ISO-8601 文字列として直列化する。backend (Cloudflare Workers) と JSON でやり取りする。
 */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString())
}

/**
 * EC プラットフォーム。`fromId` は不明IDでも AMAZON を返し NPE を防止する。
 */
@Serializable
enum class Platform(val id: String, val displayName: String, val brandColor: Long) {
    @SerialName("amazon")  AMAZON("amazon",  "Amazon", 0xFFFF9900),
    @SerialName("rakuten") RAKUTEN("rakuten", "楽天",   0xFFBF0000),
    @SerialName("yahoo")   YAHOO("yahoo",     "Yahoo!", 0xFFFF0033);

    companion object {
        fun fromIdOrNull(id: String?): Platform? = entries.firstOrNull { it.id == id }
        fun fromId(id: String?): Platform = fromIdOrNull(id) ?: AMAZON
    }
}

@Serializable
data class Product(
    val sku: String,
    val title: String,
    val platform: Platform,
    val realPrice: Long,
    val listPrice: Long,
    val shippingFee: Long = 0,
    val pointsBack: Long = 0,
    val subscribePrice: Long? = null,
    val url: String = "",
    val rating: Float? = null,
    val reviewCount: Int = 0,
    val trustScore: Int = 50,
    val deliveryDays: Int? = null,
    val imageUrl: String? = null,
    val brand: String? = null,
    val originCountry: String? = null,
    /** クーポン額 (適用済み割引、表示用) */
    val couponAmount: Long = 0,
    /** クーポンコード (空なら表示しない) */
    val couponCode: String = "",
    /** 在庫数 (null = 不明、0 = 在庫切れ) */
    val stockCount: Int? = null,
    /** JAN/EAN コード (名寄せに使用、null = 不明) */
    val janCode: String? = null,
) {
    /** 実質支払総額。3 回の算術演算のみ — recomposition コスト無視可能 */
    /** 送料込み・ポイント還元後・クーポン後の実質価格 (Compose recomposition で再計算しない) */
    val totalPrice: Long = realPrice + shippingFee - pointsBack - couponAmount
    /** プラットフォーム + SKU の一意キー — val で評価を1回に限定 */
    val key: String = "${platform.id}:$sku"
    val isInStock: Boolean get() = realPrice > 0 && stockCount != 0
    val hasCoupon: Boolean get() = couponAmount > 0 || couponCode.isNotEmpty()
}

@Serializable
data class PriceRecord(
    @SerialName("product_key") val productKey: String,
    val platform: String,
    @SerialName("list_price") val listPrice: Long,
    @SerialName("real_price") val realPrice: Long,
    @SerialName("recorded_at")
    @Serializable(with = InstantIso8601Serializer::class)
    val recordedAt: Instant,
)
