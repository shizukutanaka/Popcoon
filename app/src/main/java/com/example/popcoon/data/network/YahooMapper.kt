package com.example.popcoon.data.network

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
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
    // shipping.code == 2 を送料無料とみなす。それ以外は近似 500 円。
    val shippingFee = shipping?.takeIf { it.code == 2 }?.let { 0L } ?: 500L
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
    )
}
