package io.github.shizukutanaka.popcoon.data.network

/**
 * RakutenMapper.toProduct() の実行検証ハーネス (Android SDK 不要)。
 * run_rakuten.sh から Product.kt + RakutenMapper.kt と一緒にコンパイル・実行する。
 * availability → stockCount のマッピングと既存フィールド保全をアサートする。
 */
fun main() {
    fun item(av: Int) = RakutenResponse.RakutenItem(
        itemCode = "shop:abc", itemName = "商品X", itemPrice = 1980, itemUrl = "https://r.com/x",
        shopName = "楽天ショップ", reviewAverage = 4.5, reviewCount = 12, availability = av,
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

    println("RAKUTEN MAPPER: all assertions passed (stockCount revived from availability)")
}
