package com.example.popcoon.feature.cart

import com.example.popcoon.data.db.WatchlistItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * SmartCartService — WatchlistItem → CrossMallCartOptimizer パイプラインのテスト。
 */
class SmartCartServiceTest : StringSpec({

    fun item(
        key: String,
        title: String,
        platform: String,
        price: Long,
        listPrice: Long = price + 500,
    ) = WatchlistItem(
        productKey = key,
        sku = key,
        title = title,
        platform = platform,
        realPrice = price,
        listPrice = listPrice,
        url = "https://example.com/$key",
        imageUrl = null,
    )

    val noShipMalls = mapOf(
        "amazon"  to CrossMallCartOptimizer.MallConfig(),
        "rakuten" to CrossMallCartOptimizer.MallConfig(),
    )

    "空リストは null を返す" {
        SmartCartService.optimize(emptyList()).shouldBeNull()
    }

    "単一 item はそのプラットフォームに割り当て" {
        val items = listOf(item("a:001", "商品A", "amazon", 1000))
        val r = SmartCartService.optimize(items, noShipMalls).shouldNotBeNull()
        r.optimized.assignment.values.all { it == "amazon" } shouldBe true
    }

    "異なる商品は独立した CartItem として扱われる" {
        val items = listOf(
            item("a:001", "全く異なる商品A ブランド1", "amazon", 1000),
            item("r:002", "全く異なる商品B ブランド2", "rakuten", 800),
        )
        val r = SmartCartService.optimize(items, noShipMalls).shouldNotBeNull()
        r.cartItems.size shouldBe 2
    }

    "送料最適化: amazon に集約して送料無料ライン到達" {
        val malls = mapOf(
            "amazon"  to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 2000.0),
            "rakuten" to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 5000.0),
        )
        // 別々の商品を2点ウォッチ — amazon に集約すると 2000 以上 → 送料ゼロ
        val items = listOf(
            item("a:iPhone", "iPhone 15 128GB SIMフリー スマートフォン", "amazon", 1000),
            item("a:AirPods", "AirPods Pro 第2世代 ノイズキャンセリング", "amazon", 1000),
        )
        val r = SmartCartService.optimize(items, malls).shouldNotBeNull()
        // 2000 >= 2000 free threshold → 送料 0
        r.optimized.shippingTotal shouldBe (0.0 plusOrMinus 1e-9)
        r.optimized.total shouldBe (2000.0 plusOrMinus 1e-9)
    }

    "savings は naiveTotal - optimized.total 以上" {
        val items = listOf(
            item("a:001", "商品X", "amazon", 1500),
            item("r:002", "商品Y", "rakuten", 1200),
        )
        val r = SmartCartService.optimize(items, noShipMalls).shouldNotBeNull()
        r.savingVsNaive shouldBe (r.naiveTotal - r.optimized.total plusOrMinus 1e-9)
    }

    "savings は常に非負" {
        val items = listOf(
            item("a:001", "商品A", "amazon", 1000),
            item("r:002", "商品B", "rakuten", 800),
        )
        val r = SmartCartService.optimize(items, noShipMalls).shouldNotBeNull()
        (r.savingVsNaive >= 0.0) shouldBe true
    }

    "DEFAULT_MALL_CONFIGS はすべての主要3モールを含む" {
        SmartCartService.DEFAULT_MALL_CONFIGS.containsKey("amazon") shouldBe true
        SmartCartService.DEFAULT_MALL_CONFIGS.containsKey("rakuten") shouldBe true
        SmartCartService.DEFAULT_MALL_CONFIGS.containsKey("yahoo") shouldBe true
    }
})
