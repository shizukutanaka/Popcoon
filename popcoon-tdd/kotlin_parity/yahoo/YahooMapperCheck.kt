package com.example.popcoon.data.network

/**
 * YahooMapper.toProduct() の実行検証ハーネス (Android SDK 不要)。
 * run_yahoo.sh から Product.kt + YahooMapper.kt と一緒にコンパイル・実行する。
 * inStock → stockCount、listPrice/shipping ロジック、既存フィールド保全をアサートする。
 */
fun main() {
    fun hit(inStock: Boolean?, shipCode: Int? = null, defaultPrice: Int? = null) =
        YahooResponse.Hit(
            code = "y123", name = "商品Y", price = 3000, url = "https://y.com/y",
            priceLabel = defaultPrice?.let { YahooResponse.PriceLabel(defaultPrice = it) },
            review = YahooResponse.Review(rate = 4.2f, count = 8),
            image = YahooResponse.Image(medium = "https://img/y.jpg"),
            brand = YahooResponse.Brand(name = "ブランドY"),
            shipping = shipCode?.let { YahooResponse.Shipping(code = it) },
            inStock = inStock,
        )

    val inS = hit(true).toProduct()
    val outS = hit(false).toProduct()
    val unknown = hit(null).toProduct()

    check(inS.stockCount == null && inS.isInStock) { "inStock=true -> null/in-stock" }
    check(outS.stockCount == 0 && !outS.isInStock) { "inStock=false -> 0/out-of-stock, got ${outS.stockCount}" }
    check(unknown.stockCount == null) { "inStock=null -> null" }

    // listPrice: defaultPrice 優先、無ければ price
    check(hit(true, defaultPrice = 4000).toProduct().listPrice == 4000L) { "listPrice should use defaultPrice" }
    check(hit(true).toProduct().listPrice == 3000L) { "listPrice should fall back to price" }

    // shipping: code==2 で送料無料、それ以外 500
    check(hit(true, shipCode = 2).toProduct().shippingFee == 0L) { "shipCode 2 -> free" }
    check(hit(true, shipCode = 1).toProduct().shippingFee == 500L) { "shipCode 1 -> 500" }
    check(hit(true).toProduct().shippingFee == 500L) { "no shipping -> 500" }

    // 既存フィールド保全
    check(inS.realPrice == 3000L && inS.brand == "ブランドY" && inS.reviewCount == 8 && inS.rating == 4.2f) {
        "existing field mapping broke"
    }

    println("YAHOO MAPPER: all assertions passed (stockCount revived from inStock)")
}
