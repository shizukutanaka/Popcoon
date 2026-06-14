package com.example.popcoon.feature.points

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import java.time.LocalDate

/**
 * Standalone execution check for PointSimulator (実質価格 = sticker + shipping - pointsBack).
 * No Android SDK: PointSimulator + Product are pure. Expected values are computed
 * BY HAND from the documented rules (independent oracle), not by echoing the code.
 *
 * 2024-01 reference: Jan 1 2024 = Monday, so Jan 5 = Fri, Jan 7 = Sun, Jan 25 = Thu.
 */
private var fails = 0

private fun check(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        println("MISMATCH [$name]: expected=$expected actual=$actual")
        fails++
    }
}

private fun prod(
    platform: Platform, realPrice: Long, shipping: Long = 0, pointsBack: Long = 0,
) = Product(
    sku = "x", title = "t", platform = platform,
    realPrice = realPrice, listPrice = realPrice,
    shippingFee = shipping, pointsBack = pointsBack,
)

private fun date(d: String) = LocalDate.parse(d)

fun main() {
    // ── Rakuten: SPU only (3% of 10000 = 300), + shipping 500 ──────────────
    PointSimulator.simulate(
        prod(Platform.RAKUTEN, 10000, shipping = 500),
        PointSimulator.UserContext(rakutenSpu = 3, purchaseDate = date("2024-01-07")),
    ).let {
        check("rak spu pointsBack", 300L, it.pointsBack)
        check("rak spu effective", 10000L + 500 - 300, it.effectivePrice)
    }

    // ── Rakuten: SPU1 + 5と0のつく日(5) + diamond, all +1% each = 300 ───────
    PointSimulator.simulate(
        prod(Platform.RAKUTEN, 10000),
        PointSimulator.UserContext(rakutenSpu = 1, rakutenDiamondMember = true, purchaseDate = date("2024-01-05")),
    ).let {
        check("rak stack pointsBack", 100L + 100 + 100, it.pointsBack)
        check("rak stack effective", 10000L - 300, it.effectivePrice)
        check("rak stack sources", 3, it.breakdown.size)
    }

    // ── Rakuten: floor-of-each-source. spu15 of 9999 = 1499.85 -> 1499;
    //    5と0(day10) = 99.99 -> 99. total 1598 ───────────────────────────────
    PointSimulator.simulate(
        prod(Platform.RAKUTEN, 9999),
        PointSimulator.UserContext(rakutenSpu = 15, purchaseDate = date("2024-01-10")),
    ).let {
        check("rak truncation pointsBack", 1499L + 99, it.pointsBack)
    }

    // ── Rakuten: spu out-of-range display now matches credited rate ─────────
    PointSimulator.simulate(
        prod(Platform.RAKUTEN, 10000),
        PointSimulator.UserContext(rakutenSpu = 20, purchaseDate = date("2024-01-07")),
    ).let {
        check("rak spu20 credited 15%", 1500L, it.pointsBack)
        check("rak spu20 display coerced", "15.0%", it.breakdown.first().rateString)
    }
    PointSimulator.simulate(
        prod(Platform.RAKUTEN, 10000),
        PointSimulator.UserContext(rakutenSpu = 0, purchaseDate = date("2024-01-07")),
    ).let {
        check("rak spu0 credited 1%", 100L, it.pointsBack)
        check("rak spu0 display coerced", "1.0%", it.breakdown.first().rateString)
    }

    // ── Yahoo: PayPay1% + 5のつく日4% on a non-Sunday (Jan 25 = Thu) ────────
    PointSimulator.simulate(
        prod(Platform.YAHOO, 5000),
        PointSimulator.UserContext(purchaseDate = date("2024-01-25")),
    ).let {
        check("yahoo 5day pointsBack", 50L + 200, it.pointsBack)
    }

    // ── Yahoo: Sunday(Jan 7) + premium + softbank. 1% + 5%(日曜) + 2% + 5% ──
    PointSimulator.simulate(
        prod(Platform.YAHOO, 5000),
        PointSimulator.UserContext(yahooPremium = true, paypaySoftbank = true, purchaseDate = date("2024-01-07")),
    ).let {
        check("yahoo sunday stack pointsBack", 50L + 250 + 100 + 250, it.pointsBack)
    }

    // ── Amazon: per-product pointsBack, rate displayed ─────────────────────
    PointSimulator.simulate(
        prod(Platform.AMAZON, 8000, pointsBack = 400),
    ).let {
        check("amazon pointsBack", 400L, it.pointsBack)
        check("amazon rate display", "5.0%", it.breakdown.first().rateString)
        check("amazon effective", 8000L - 400, it.effectivePrice)
    }
    // Amazon: zero pointsBack -> no source
    PointSimulator.simulate(prod(Platform.AMAZON, 8000, pointsBack = 0)).let {
        check("amazon zero sources", 0, it.breakdown.size)
        check("amazon zero effective", 8000L, it.effectivePrice)
    }

    // ── Effective price floors at 0 when points exceed sticker+shipping ────
    PointSimulator.simulate(prod(Platform.AMAZON, 10, pointsBack = 50)).let {
        check("effective floor at 0", 0L, it.effectivePrice)
    }

    if (fails == 0) {
        println("POINT SIMULATOR: all assertions passed")
    } else {
        println("POINT SIMULATOR: $fails assertion(s) FAILED")
        kotlin.system.exitProcess(1)
    }
}
