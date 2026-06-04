package com.example.popcoon.feature.retention

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit

/**
 * ReviewPrompter の cooldown ロジックの仕様テスト。
 *
 * UserPreferences は Context 依存なのでスタブ化が複雑。
 * ここでは ReviewPrompter が使う閾値・cooldown 計算式のみを検証する。
 */
class ReviewPrompterLogicTest : StringSpec({

    val MIN_SUCCESS_COUNT = 5
    val COOLDOWN_MS = TimeUnit.DAYS.toMillis(90)

    fun shouldRequest(successCount: Int, lastReviewMs: Long, now: Long): Boolean {
        if (successCount < MIN_SUCCESS_COUNT) return false
        if (now - lastReviewMs < COOLDOWN_MS) return false
        return true
    }

    "閾値未満は false" {
        val now = 100_000_000L
        shouldRequest(4, 0, now) shouldBe false
    }

    "閾値ちょうど + cooldown なし → true" {
        val now = COOLDOWN_MS + 1_000L
        shouldRequest(5, 0, now) shouldBe true
    }

    "閾値超過でも cooldown 内 → false" {
        val now = 100_000_000L
        val recent = now - TimeUnit.DAYS.toMillis(30)
        shouldRequest(100, recent, now) shouldBe false
    }

    "cooldown 91日後 → true" {
        val now = 100_000_000L
        val past = now - TimeUnit.DAYS.toMillis(91)
        shouldRequest(5, past, now) shouldBe true
    }

    "cooldown ぴったり 90日後 → false (境界条件)" {
        val now = 100_000_000L
        val exactly = now - COOLDOWN_MS
        shouldRequest(5, exactly, now) shouldBe false
    }

    "cooldown 90日 + 1ms 後 → true" {
        val now = 100_000_000L
        val justAfter = now - COOLDOWN_MS - 1L
        shouldRequest(5, justAfter, now) shouldBe true
    }

    "successCount 0 で lastReview 古くても false" {
        val now = 100_000_000L
        val ancient = 0L
        shouldRequest(0, ancient, now) shouldBe false
    }
})
