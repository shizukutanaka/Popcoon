package io.github.shizukutanaka.popcoon.data.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Product / Platform の純ロジック検証。
 * 全画面で使われる派生プロパティと、NPE 防止の fromId フォールバック契約を固定する。
 */
class ProductTest : StringSpec({

    fun product(
        realPrice: Long = 1000,
        shippingFee: Long = 0,
        pointsBack: Long = 0,
        couponAmount: Long = 0,
        couponCode: String = "",
        stockCount: Int? = null,
        sku: String = "B0X",
        platform: Platform = Platform.AMAZON,
    ) = Product(
        sku = sku, title = "商品", platform = platform,
        realPrice = realPrice, listPrice = realPrice,
        shippingFee = shippingFee, pointsBack = pointsBack,
        couponAmount = couponAmount, couponCode = couponCode,
        stockCount = stockCount,
    )

    // ── totalPrice ────────────────────────────────────────────────────────
    "totalPrice = realPrice + 送料 − ポイント − クーポン" {
        product(realPrice = 1000, shippingFee = 300, pointsBack = 100, couponAmount = 200)
            .totalPrice shouldBe 1000L
    }

    "totalPrice: 送料/ポイント/クーポンが全て 0 なら realPrice と一致" {
        product(realPrice = 4980).totalPrice shouldBe 4980L
    }

    // ── key ───────────────────────────────────────────────────────────────
    "key は platform.id:sku" {
        product(sku = "B0ABC", platform = Platform.RAKUTEN).key shouldBe "rakuten:B0ABC"
    }

    // ── isInStock ─────────────────────────────────────────────────────────
    "stockCount=null (不明) は在庫ありとみなす" {
        product(realPrice = 1000, stockCount = null).isInStock shouldBe true
    }

    "stockCount=0 は在庫切れ" {
        product(realPrice = 1000, stockCount = 0).isInStock shouldBe false
    }

    "realPrice<=0 は在庫切れ" {
        product(realPrice = 0, stockCount = 5).isInStock shouldBe false
    }

    // ── hasCoupon ─────────────────────────────────────────────────────────
    "hasCoupon: 金額 or コードのどちらかで true" {
        product(couponAmount = 100).hasCoupon shouldBe true
        product(couponCode = "SAVE10").hasCoupon shouldBe true
        product().hasCoupon shouldBe false
    }

    // ── Platform.fromId フォールバック契約 (NPE 防止) ────────────────────────
    "fromId: 既知 ID は対応する Platform" {
        Platform.fromId("amazon") shouldBe Platform.AMAZON
        Platform.fromId("rakuten") shouldBe Platform.RAKUTEN
        Platform.fromId("yahoo") shouldBe Platform.YAHOO
    }

    "fromId: 未知 ID / null は AMAZON にフォールバック (NPE 防止契約)" {
        Platform.fromId("unknown") shouldBe Platform.AMAZON
        Platform.fromId(null) shouldBe Platform.AMAZON
        Platform.fromId("") shouldBe Platform.AMAZON
    }

    "fromIdOrNull: 未知 ID / null は null" {
        Platform.fromIdOrNull("unknown").shouldBeNull()
        Platform.fromIdOrNull(null).shouldBeNull()
        Platform.fromIdOrNull("rakuten") shouldBe Platform.RAKUTEN
    }
})
