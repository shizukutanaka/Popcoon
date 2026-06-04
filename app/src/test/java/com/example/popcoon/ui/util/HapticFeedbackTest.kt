package com.example.popcoon.ui.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * HapticFeedback — Context が必要なため Context なしで呼べる部分のみテスト。
 * 実機テストは Instrumentation に委ねる。
 */
class HapticFeedbackTest : StringSpec({

    "Vibration effect 定数が定義されている (API 26+ 確認)" {
        // VibrationEffect の定数値が変わっていないことを確認
        android.os.VibrationEffect.EFFECT_CLICK shouldBe 0
        android.os.VibrationEffect.EFFECT_HEAVY_CLICK shouldBe 5
        android.os.VibrationEffect.EFFECT_DOUBLE_CLICK shouldBe 1
    }
})
