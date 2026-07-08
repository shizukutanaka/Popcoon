package io.github.shizukutanaka.popcoon.data.network

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import kotlinx.serialization.Serializable

/**
 * 楽天 IchibaItem/Search レスポンスの DTO と Product への純粋マッピング。
 *
 * ktor / BuildConfig 非依存に切り出すことで、Android SDK 無しでもコンパイル・実行検証できる
 * (popcoon-tdd/kotlin_parity/run_rakuten.sh)。RakutenClient は IO のみを担い、
 * 変換ロジックはこの純粋関数に集約する。
 */
@Serializable
internal data class RakutenResponse(val Items: List<ItemContainer>) {
    @Serializable
    internal data class ItemContainer(val Item: RakutenItem)

    @Serializable
    internal data class RakutenItem(
        val itemCode: String,
        val itemName: String,
        val itemPrice: Int,
        val itemUrl: String,
        val shopName: String,
        val reviewAverage: Double? = null,
        val reviewCount: Int? = null,
        // availability: 1=在庫あり, 0=在庫切れ。API が返すが従来 DTO で取りこぼしていた。
        val availability: Int = 1,
        val mediumImageUrls: List<ImageUrl> = emptyList(),
    )

    @Serializable
    internal data class ImageUrl(val imageUrl: String)
}

/**
 * RakutenItem → Product。在庫情報を API の availability から復元する。
 *
 * 従来 stockCount は常に null で、SortAndFilter の「在庫切れ除外」(stockCount==0) が
 * 死蔵していた (IMPROVEMENTS.md Tier 8/9 の幻フィールド問題)。availability を写すことで
 * 在庫切れ商品をデフォルトで検索結果から除外できるようになる。
 *
 * 注: pointsBack は依然 0 固定。pointRate からの算出は product.totalPrice を変えて UI 全体に
 * 波及するため、CI でレンダリングを検証できるようになってから対応する (TODO)。
 */
internal fun RakutenResponse.RakutenItem.toProduct(): Product = Product(
    sku = itemCode,
    title = itemName,
    platform = Platform.RAKUTEN,
    realPrice = itemPrice.toLong(),
    listPrice = itemPrice.toLong(),  // 楽天は定価を返さない
    shippingFee = 0L,
    pointsBack = 0L,
    url = itemUrl,
    rating = reviewAverage?.toFloat(),
    reviewCount = reviewCount ?: 0,
    imageUrl = mediumImageUrls.firstOrNull()?.imageUrl,
    brand = shopName,
    // availability 0=在庫切れ → stockCount=0 (在庫切れ除外フィルタが機能する)。
    // 1=在庫あり → null (数は不明だが isInStock は realPrice>0 && stockCount!=0 で true)。
    stockCount = if (availability == 0) 0 else null,
)
