package com.example.popcoon.feature.points

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class PointSimulatorTest : StringSpec({

    fun product(platform: Platform, price: Long, shipping: Long = 0L, points: Long = 0L) =
        Product(
            sku = "X", title = "テスト", platform = platform,
            realPrice = price, listPrice = price,
            shippingFee = shipping, pointsBack = points,
        )

    "楽天 SPU 1倍 = 1% 還元" {
        val ctx = PointSimulator.UserContext(
            rakutenSpu = 1, purchaseDate = LocalDate.of(2026, 4, 1),
        )
        val r = PointSimulator.simulate(product(Platform.RAKUTEN, 10_000), ctx)
        r.pointsBack shouldBe 100L
        r.effectivePrice shouldBe 9_900L
    }

    "楽天 SPU 5倍 + 5と0のつく日 + ダイヤモンド = 7%" {
        val ctx = PointSimulator.UserContext(
            rakutenSpu = 5,
            rakutenDiamondMember = true,
            purchaseDate = LocalDate.of(2026, 4, 5),
        )
        val r = PointSimulator.simulate(product(Platform.RAKUTEN, 10_000), ctx)
        r.pointsBack shouldBe 700L  // 5% + 1% + 1%
        r.breakdown.size shouldBe 3
    }

    "Yahoo 5のつく日 + プレミアム = 7%" {
        val ctx = PointSimulator.UserContext(
            yahooPremium = true,
            purchaseDate = LocalDate.of(2026, 4, 5),
        )
        val r = PointSimulator.simulate(product(Platform.YAHOO, 10_000), ctx)
        // 1% (基本) + 4% (5のつく日) + 2% (プレミアム) = 7%
        r.pointsBack shouldBe 700L
    }

    "Yahoo SoftBank会員 + 日曜 = 11%" {
        val ctx = PointSimulator.UserContext(
            paypaySoftbank = true,
            purchaseDate = LocalDate.of(2026, 4, 12),  // 日曜
        )
        val r = PointSimulator.simulate(product(Platform.YAHOO, 10_000), ctx)
        // 1% (基本) + 5% (日曜) + 5% (SoftBank) = 11%
        r.pointsBack shouldBe 1100L
    }

    "Amazon 固定ポイント還元のみ" {
        val ctx = PointSimulator.UserContext()
        val r = PointSimulator.simulate(
            product(Platform.AMAZON, 5000, points = 250L), ctx,
        )
        r.pointsBack shouldBe 250L
        r.effectivePrice shouldBe 4_750L
    }

    "送料が実質価格に加算される" {
        val ctx = PointSimulator.UserContext(rakutenSpu = 1)
        val r = PointSimulator.simulate(
            product(Platform.RAKUTEN, 5000, shipping = 500L), ctx,
        )
        // 5,000 + 500 - 50 (1%) = 5,450
        r.effectivePrice shouldBe 5_450L
    }

    // 回帰防止: 旧テストは rakutenSpu=15 で effectivePrice >= 0 しか確認せず、
    // SPU 計算を完全に無効化しても緑だった (10_000 - 0 = 10_000 >= 0 は常に真)。
    // 識別テストは SPU=15 が生む pointsBack の具体値を検証し、計算削除で必ず落ちる。
    "SPU 15 = 15% 還元 (識別テスト: 具体値で SPU 計算ロジックを固定)" {
        val ctx = PointSimulator.UserContext(
            rakutenSpu = 15,
            purchaseDate = LocalDate.of(2026, 4, 1),  // 1日 = 5と0のつく日でない
        )
        val r = PointSimulator.simulate(product(Platform.RAKUTEN, 10_000), ctx)
        r.pointsBack shouldBe 1_500L      // 10,000 × 15% = 1,500
        r.effectivePrice shouldBe 8_500L  // 10,000 − 1,500
    }

    // 実質価格クランプの識別テスト: Amazon pointsBack が price を上回る場合に 0 以下にならない。
    // SPU=15 の楽天商品は最大 15% 還元なので sticker を超えず、こちらが本来の clamp テスト。
    "Amazon pointsBack が price を超過しても effectivePrice は 0 (max clamp)" {
        val highPointsProduct = Product(
            sku = "B0CLAMP01", title = "超高ポイント商品", platform = Platform.AMAZON,
            listPrice = 100, realPrice = 100, pointsBack = 9_999,
        )
        val r = PointSimulator.simulate(highPointsProduct)
        r.effectivePrice shouldBe 0L
    }

    "breakdown は透明性のため全項目を返す" {
        val ctx = PointSimulator.UserContext(
            rakutenSpu = 5,
            rakutenDiamondMember = true,
            purchaseDate = LocalDate.of(2026, 4, 25),  // 25日 = 5と0
        )
        val r = PointSimulator.simulate(product(Platform.RAKUTEN, 1000), ctx)
        r.breakdown.size shouldBe 3
        r.breakdown.map { it.name }.any { it.contains("SPU") } shouldBe true
        r.breakdown.map { it.name }.any { it.contains("5と0") } shouldBe true
        r.breakdown.map { it.name }.any { it.contains("ダイヤモンド") } shouldBe true
    }
})
