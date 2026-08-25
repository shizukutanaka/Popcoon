package io.github.shizukutanaka.popcoon.data.network

import io.github.shizukutanaka.popcoon.data.model.SHIPPING_APPROX_YEN

/**
 * RakutenMapper.toProduct() の実行検証ハーネス (Android SDK 不要)。
 * run_rakuten.sh から Product.kt + RakutenMapper.kt と一緒にコンパイル・実行する。
 * availability → stockCount / postageFlag → shippingFee のマッピングと
 * 既存フィールド保全をアサートする。
 */
fun main() {
    fun item(av: Int, postage: Int = 0) = RakutenResponse.RakutenItem(
        itemCode = "shop:abc", itemName = "商品X", itemPrice = 1980, itemUrl = "https://r.com/x",
        shopName = "楽天ショップ", reviewAverage = 4.5, reviewCount = 12, availability = av,
        postageFlag = postage,
    )
    val inStock = item(1).toProduct()
    val outStock = item(0).toProduct()
    val defAvail = RakutenResponse.RakutenItem("c", "t", 1000, "u", "s").toProduct()

    check(inStock.stockCount == null) { "in-stock stockCount should be null, got ${inStock.stockCount}" }
    check(inStock.isInStock) { "in-stock isInStock should be true" }
    check(outStock.stockCount == 0) { "out-of-stock stockCount should be 0, got ${outStock.stockCount}" }
    check(!outStock.isInStock) { "out-of-stock isInStock should be false" }
    check(defAvail.stockCount == null) { "default availability(1) -> null" }
    check(inStock.realPrice == 1980L && inStock.brand == "楽天ショップ" && inStock.reviewCount == 12) {
        "existing field mapping broke"
    }
    check(inStock.pointsBack == 0L) { "pointsBack intentionally still 0 (pointRate is a CI-gated follow-up)" }

    // postageFlag → shippingFee。以前は 0L 固定で、Yahoo だけが送料 500 円を
    // 計上していたため PointSimulator.effectivePrice (検索の並び順・名寄せ代表選択・
    // カート最適化の基準) が Yahoo に不利へ系統的に偏っていた。
    val postageIncluded = item(1, postage = 0).toProduct()
    val postageExtra = item(1, postage = 1).toProduct()
    check(postageIncluded.shippingFee == 0L) {
        "postageFlag=0 (送料込み) -> 0, got ${postageIncluded.shippingFee}"
    }
    check(postageExtra.shippingFee == SHIPPING_APPROX_YEN) {
        "postageFlag=1 (送料別) -> $SHIPPING_APPROX_YEN, got ${postageExtra.shippingFee}"
    }
    check(defAvail.shippingFee == 0L) { "postageFlag 既定 (0) -> 0 で従来挙動を保つ" }
    // totalPrice = realPrice + shippingFee - pointsBack - couponAmount まで通ること
    check(postageExtra.totalPrice == 1980L + SHIPPING_APPROX_YEN) {
        "totalPrice should include shipping, got ${postageExtra.totalPrice}"
    }
    check(postageIncluded.totalPrice == 1980L) {
        "送料込みなら totalPrice は本体価格のまま, got ${postageIncluded.totalPrice}"
    }
    // Yahoo と同じ基準であること (片方だけ送料を載せると比較が歪む)
    check(SHIPPING_APPROX_YEN == 500L) { "全モール共通の概算値であること" }

    println("RAKUTEN MAPPER: all assertions passed (stockCount + shippingFee revived)")
}
