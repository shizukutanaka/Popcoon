package io.github.shizukutanaka.popcoon.data.network

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.data.model.SHIPPING_APPROX_YEN
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
        // postageFlag: 0=送料込み(無料), 1=送料別。availability と同じく **API は返すのに
        // DTO で取りこぼしていた**。既定 0 は「送料込み」= 従来と同じ挙動 (安全側)。
        val postageFlag: Int = 0,
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
 * 送料も postageFlag から復元する。**モール間の比較基準を揃えるため**:
 * `PointSimulator.effectivePrice = sticker + shipping - points` は検索結果の並び順・
 * 名寄せグループの代表選択・スマートカート最適化のすべての基準になっているが、
 * 以前は楽天だけ `shippingFee = 0L` 固定で、Yahoo は送料無料フラグが無ければ 500 円を
 * 計上していた。つまり **同条件でも Yahoo だけが 500 円不利**に並び、実際には楽天の方が
 * 高い場合でも楽天が安く見える。横断価格比較がこのアプリの中心機能なので、
 * 情報が API から取れるのに片方だけ無視するのは比較そのものを歪める。
 *
 * 注: pointsBack は依然 0 固定。ただし **これは比較を歪めない** —
 * PointSimulator が SPU / PayPay / Amazon ポイントを 3 モールとも自前で算出しており、
 * `Product.pointsBack` を参照しないため。pointRate からの算出は
 * product.totalPrice を変えて UI 全体に波及するため CI 後に対応する (TODO)。
 */
internal fun RakutenResponse.RakutenItem.toProduct(): Product = Product(
    sku = itemCode,
    title = itemName,
    platform = Platform.RAKUTEN,
    realPrice = itemPrice.toLong(),
    listPrice = itemPrice.toLong(),  // 楽天は定価を返さない
    // 送料別 (postageFlag=1) なら概算を計上する。0 (送料込み) は 0 のまま。
    shippingFee = if (postageFlag == 1) SHIPPING_APPROX_YEN else 0L,
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
