package com.example.popcoon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CurrencyFormatterTest : StringSpec({

    "yen: 1234 → ¥1,234" {
        CurrencyFormatter.yen(1234) shouldBe "¥1,234"
    }

    "yen: 0 → ¥0" {
        CurrencyFormatter.yen(0) shouldBe "¥0"
    }

    "yen: 1000000 → ¥1,000,000" {
        CurrencyFormatter.yen(1_000_000) shouldBe "¥1,000,000"
    }

    "yenAccessible: 読み上げ用に「円」付き" {
        CurrencyFormatter.yenAccessible(3980) shouldBe "3,980円"
    }

    "yenDiff: 正の差額は + 付き" {
        CurrencyFormatter.yenDiff(500) shouldBe "+¥500"
    }

    "yenDiff: 負の差額は - 付き" {
        CurrencyFormatter.yenDiff(-300) shouldBe "-¥300"
    }

    "yenDiff: ゼロは + 付き" {
        CurrencyFormatter.yenDiff(0) shouldBe "+¥0"
    }

    "discountPercent: 5000 → 4000 は 20% OFF" {
        CurrencyFormatter.discountPercent(5000, 4000) shouldBe "20% OFF"
    }

    "discountPercent: 値上がり時は空文字" {
        CurrencyFormatter.discountPercent(3000, 4000) shouldBe ""
    }

    "discountPercent: original 0 は空文字" {
        CurrencyFormatter.discountPercent(0, 1000) shouldBe ""
    }

    "pointsBack: フォーマット確認" {
        CurrencyFormatter.pointsBack(100, "1.0%") shouldBe "+¥100 (1.0%)"
    }
})
