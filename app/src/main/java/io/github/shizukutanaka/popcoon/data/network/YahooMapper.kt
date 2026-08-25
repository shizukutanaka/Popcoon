package io.github.shizukutanaka.popcoon.data.network

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.data.model.SHIPPING_APPROX_YEN
import kotlinx.serialization.Serializable

/**
 * Yahoo!ショッピング V3 itemSearch レスポンスの DTO と Product への純粋マッピング。
 *
 * ktor / BuildConfig 非依存に切り出し、Android SDK 無しでコンパイル・実行検証できる
 * (popcoon-tdd/kotlin_parity/run_yahoo.sh)。YahooClient は IO のみを担う。
 */
@Serializable
internal data class YahooResponse(val hits: List<Hit>) {
    @Serializable
    internal data class Hit(
        val code: String,
        val name: String,
        val price: Int,
        val url: String,
        val priceLabel: PriceLabel? = null,
        val review: Review? = null,
        val image: Image? = null,
        val brand: Brand? = null,
        val shipping: Shipping? = null,
        // inStock: Yahoo V3 が返す在庫真偽 (要・実 API 確認)。従来 DTO で取りこぼしていた。
        // ignoreUnknownKeys + nullable 既定のため、フィールド名が違っても無害 (null のまま)。
        val inStock: Boolean? = null,
        // janCode: Yahoo V3 商品検索は JAN を返す。ProductMatcher の最優先一致 (バーコード) に使う。
        // 従来 DTO で取りこぼし、横断名寄せの確実シグナルが死んでいた。nullable 既定で無害。
        val janCode: String? = null,
    )
    @Serializable internal data class PriceLabel(val defaultPrice: Int? = null, val premiumPrice: Int? = null)
    @Serializable internal data class Review(val rate: Float? = null, val count: Int? = null)
    @Serializable internal data class Image(val medium: String? = null)
    @Serializable internal data class Brand(val name: String)
    @Serializable internal data class Shipping(val code: Int)
}

/**
 * Yahoo Hit → Product。在庫情報を inStock から復元する
 * (従来 stockCount は常に null で SortAndFilter の在庫切れ除外が死蔵していた)。
 *
 * 注: pointsBack は依然 0 固定 (product.totalPrice を変え UI へ波及するため CI 後に対応)。
 */
internal fun YahooResponse.Hit.toProduct(): Product {
    // defaultPrice = 通常価格(参考価格)。premiumPrice はプレミアム会員割引で realPrice を
    // 下回り得るため list price には使わない。
    val computedListPrice = priceLabel?.defaultPrice ?: price
    // shipping.code == 2 を送料無料とみなす。それ以外は概算を計上する。
    // 概算値は全モール共有 (data.model.SHIPPING_APPROX_YEN) — 片方のモールだけ送料を載せると
    // PointSimulator.effectivePrice (検索の並び順・代表選択・カート最適化の基準) が
    // そのモールに不利に偏るため、同じ基準でなければならない。
    val shippingFee = shipping?.takeIf { it.code == 2 }?.let { 0L } ?: SHIPPING_APPROX_YEN
    return Product(
        sku = code,
        title = name,
        platform = Platform.YAHOO,
        realPrice = price.toLong(),
        listPrice = computedListPrice.toLong(),
        shippingFee = shippingFee,
        pointsBack = 0L,
        url = url,
        rating = review?.rate,
        reviewCount = review?.count ?: 0,
        imageUrl = image?.medium,
        brand = brand?.name,
        // inStock==false → stockCount=0 (在庫切れ除外フィルタが機能)。true/null → null。
        stockCount = if (inStock == false) 0 else null,
        // JAN を正規化して名寄せの確実シグナルに供給 (FallbackScraper の gtin と同じ正規化)。
        janCode = normalizeGtin(janCode),
    )
}
