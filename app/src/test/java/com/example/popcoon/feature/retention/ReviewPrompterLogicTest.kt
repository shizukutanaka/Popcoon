package com.example.popcoon.feature.retention

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * ReviewPrompter.shouldRequestNow() の仕様テスト。
 *
 * 本番コード (ReviewPrompter.Companion.shouldRequestNow) を直接呼ぶことで
 * 閾値・cooldown を変更した際に回帰を確実に検出する。
 * UserPreferences は Context 依存なので Instrumentation テストに委ねる。
 */
class ReviewPrompterLogicTest : StringSpec({

    val cooldown = ReviewPrompter.COOLDOWN_MS

    "閾値未満は false" {
        ReviewPrompter.shouldRequestNow(4, 0, cooldown + 1_000L) shouldBe false
    }

    "閾値ちょうど + cooldown なし → true" {
        ReviewPrompter.shouldRequestNow(5, 0, cooldown + 1_000L) shouldBe true
    }

    "閾値超過でも cooldown 内 → false" {
        val now = 100_000_000L
        val recent = now - (cooldown / 3)  // 30日前
        ReviewPrompter.shouldRequestNow(100, recent, now) shouldBe false
    }

    "cooldown 91日後 → true" {
        val now = 100_000_000L
        ReviewPrompter.shouldRequestNow(5, now - cooldown * 91 / 90, now) shouldBe true
    }

    "cooldown ぴったり 90日後 → false (境界条件: < ではなく <=)" {
        val now = 100_000_000L
        ReviewPrompter.shouldRequestNow(5, now - cooldown, now) shouldBe false
    }

    "cooldown 90日 + 1ms 後 → true" {
        val now = 100_000_000L
        ReviewPrompter.shouldRequestNow(5, now - cooldown - 1L, now) shouldBe true
    }

    "successCount 0 で lastReview 古くても false" {
        ReviewPrompter.shouldRequestNow(0, 0L, cooldown * 10) shouldBe false
    }

    "MIN_SUCCESS_COUNT は 5 (定数契約)" {
        ReviewPrompter.MIN_SUCCESS_COUNT shouldBe 5
    }
})
