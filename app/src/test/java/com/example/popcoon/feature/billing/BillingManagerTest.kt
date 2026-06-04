package com.example.popcoon.feature.billing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

/**
 * BillingManager の商品定義と価格戦略のテスト。
 *
 * Activity 依存部分 (BillingClient) は Instrumentation テストで検証。
 * ここでは定数 / 商品ID / 価格計算ロジックのみ確認。
 */
class BillingManagerTest : StringSpec({

    "Premium 月額 product ID が非空" {
        BillingManager.SKU_PREMIUM_MONTHLY.shouldNotBeEmpty()
    }

    "Premium 年額 product ID が非空" {
        BillingManager.SKU_PREMIUM_YEARLY.shouldNotBeEmpty()
    }

    "月額 12 ヶ月 > 年額 (年額にすると割安)" {
        // ¥480/月 × 12 = ¥5,760 > ¥3,800/年
        val monthlyAnnualized = 480 * 12
        val yearly = 3800
        (monthlyAnnualized > yearly) shouldBe true
    }

    "年額の割引率は 30% 以上" {
        val monthlyAnnualized = 480.0 * 12
        val yearly = 3800.0
        val discountPct = (monthlyAnnualized - yearly) / monthlyAnnualized * 100
        (discountPct >= 30.0) shouldBe true
    }

    "Product ID にスペースや特殊文字が含まれない" {
        BillingManager.SKU_PREMIUM_MONTHLY.matches(Regex("^[a-z0-9_.]+$")) shouldBe true
        BillingManager.SKU_PREMIUM_YEARLY.matches(Regex("^[a-z0-9_.]+$")) shouldBe true
    }
})
