package io.github.shizukutanaka.popcoon.ui

import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternDetector
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * DarkPatternDetector.Warning.toLabelResource() のテスト。
 *
 * Warning.label (日本語固定文字列) を検索結果・商品詳細画面に直接表示すると
 * EN/KO/ZH ロケールに日本語が漏れる (商用リリース監査で発見)。このマッピングが
 * type (+ DRIP_PRICING は severity) から正しい文字列リソースへ変換することを固定する。
 */
class DarkPatternLabelsTest : StringSpec({

    "ALWAYS_ON_DISCOUNT は dp_always_on_discount にマップされる" {
        val w = DarkPatternDetector.Warning(
            DarkPatternDetector.WarningType.ALWAYS_ON_DISCOUNT, "常設セール", DarkPatternDetector.Severity.HIGH,
        )
        w.toLabelResource() shouldBe (R.string.dp_always_on_discount to emptyList<Any>())
    }

    "INFLATED_LIST_PRICE は dp_inflated_list_price にマップされる" {
        val w = DarkPatternDetector.Warning(
            DarkPatternDetector.WarningType.INFLATED_LIST_PRICE, "参考価格誇張", DarkPatternDetector.Severity.HIGH,
        )
        w.toLabelResource() shouldBe (R.string.dp_inflated_list_price to emptyList<Any>())
    }

    "PRE_SALE_MARKUP は dp_pre_sale_markup にマップされる" {
        val w = DarkPatternDetector.Warning(
            DarkPatternDetector.WarningType.PRE_SALE_MARKUP, "セール前値上げ", DarkPatternDetector.Severity.HIGH,
        )
        w.toLabelResource() shouldBe (R.string.dp_pre_sale_markup to emptyList<Any>())
    }

    "CHARM_PRICING は dp_charm_pricing にマップされる" {
        val w = DarkPatternDetector.Warning(
            DarkPatternDetector.WarningType.CHARM_PRICING, "端数価格", DarkPatternDetector.Severity.LOW,
        )
        w.toLabelResource() shouldBe (R.string.dp_charm_pricing to emptyList<Any>())
    }

    "FAKE_SCARCITY は dp_fake_scarcity にマップされる" {
        val w = DarkPatternDetector.Warning(
            DarkPatternDetector.WarningType.FAKE_SCARCITY, "在庫を煽る表現", DarkPatternDetector.Severity.MEDIUM,
        )
        w.toLabelResource() shouldBe (R.string.dp_fake_scarcity to emptyList<Any>())
    }

    "COUNTDOWN_MANIPULATION は dp_countdown_manipulation にマップされる" {
        val w = DarkPatternDetector.Warning(
            DarkPatternDetector.WarningType.COUNTDOWN_MANIPULATION, "時間制限を煽る表現", DarkPatternDetector.Severity.MEDIUM,
        )
        w.toLabelResource() shouldBe (R.string.dp_countdown_manipulation to emptyList<Any>())
    }

    "DRIP_PRICING (HIGH, 実質+40%) は dp_drip_pricing_high + パーセント引数にマップされる" {
        val w = DarkPatternDetector.detectDripPricing(basePrice = 1000, totalPrice = 1400)
            ?: error("expected a DRIP_PRICING warning at +40%")
        w.severity shouldBe DarkPatternDetector.Severity.HIGH
        w.toLabelResource() shouldBe (R.string.dp_drip_pricing_high to listOf(40))
    }

    "DRIP_PRICING (MEDIUM, 実質+20%) は dp_drip_pricing_medium + パーセント引数にマップされる" {
        val w = DarkPatternDetector.detectDripPricing(basePrice = 1000, totalPrice = 1200)
            ?: error("expected a DRIP_PRICING warning at +20%")
        w.severity shouldBe DarkPatternDetector.Severity.MEDIUM
        w.toLabelResource() shouldBe (R.string.dp_drip_pricing_medium to listOf(20))
    }
})
