package com.example.popcoon.feature.scorer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Python 参照 (proto_seasonal_signal.py) との完全一致パリティテスト。
 * ゴールデン値は PORTING_SPEC.md A5（Python 実行で取得）。
 */
class SeasonalDowSignalTest : StringSpec({

    // 4週間(28日): 平日(0..4)=1000, 週末(5,6)=800 を (i%7, 値) で
    val history = (0 until 28).map { i -> (i % 7) to if (i % 7 in 0..4) 1000.0 else 800.0 }

    "パリティ: 土(5) -> 10 (上限クランプ)" {
        SeasonalDowSignal.signal(history, 5) shouldBe 10
    }

    "パリティ: 月(0) -> -6" {
        SeasonalDowSignal.signal(history, 0) shouldBe -6
    }

    "パリティ: 木(3) -> -6" {
        SeasonalDowSignal.signal(history, 3) shouldBe -6
    }

    "履歴不足は中立" {
        SeasonalDowSignal.signal(List(5) { 0 to 1000.0 }, 0) shouldBe 0
    }

    "平坦価格は中立" {
        val flat = (0 until 28).map { i -> (i % 7) to 1000.0 }
        SeasonalDowSignal.signal(flat, 3) shouldBe 0
    }

    "曜日サンプル過少は中立" {
        val h = List(13) { 1 to 1000.0 } + listOf(4 to 700.0)
        SeasonalDowSignal.signal(h, 4) shouldBe 0
    }
})
