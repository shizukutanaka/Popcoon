package com.example.popcoon.feature.prediction

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Python 参照 (proto_seasonal_decomp_forecast.py) との完全一致パリティテスト。
 * ゴールデン値は PORTING_SPEC.md A1（Python 実行で取得）。
 */
class SeasonalDecompForecastTest : StringSpec({

    // 4週間(28日): 平日(0..4)=1000, 週末(5,6)=800
    val weekend = (0 until 28).map { i -> if (i % 7 in 0..4) 1000.0 else 800.0 }

    "パリティ: 週次パターンを再現 [1000x5, 800, 800]" {
        val out = SeasonalDecompForecast.forecast(weekend, 7, 7)
        out[0] shouldBe (1000.0 plusOrMinus 1e-6)
        out[4] shouldBe (1000.0 plusOrMinus 1e-6)
        out[5] shouldBe (800.0 plusOrMinus 1e-6)
        out[6] shouldBe (800.0 plusOrMinus 1e-6)
    }

    "純線形トレンドを外挿" {
        val lin = (0 until 28).map { it * 10.0 }
        val out = SeasonalDecompForecast.forecast(lin, 3, 7)
        out[0] shouldBe (280.0 plusOrMinus 1e-6)
        out[1] shouldBe (290.0 plusOrMinus 1e-6)
        out[2] shouldBe (300.0 plusOrMinus 1e-6)
    }

    "履歴不足は直近値でフラット" {
        SeasonalDecompForecast.forecast(listOf(500.0, 510.0, 505.0), 4, 7) shouldBe
            listOf(505.0, 505.0, 505.0, 505.0)
    }

    "空履歴は空" {
        SeasonalDecompForecast.forecast(emptyList(), 7) shouldBe emptyList()
    }

    "出力長は horizon" {
        SeasonalDecompForecast.forecast(weekend, 5, 7) shouldHaveSize 5
    }

    "period<=1 は純線形" {
        val lin = (0 until 28).map { it * 10.0 }
        SeasonalDecompForecast.forecast(lin, 2, 1)[0] shouldBe (280.0 plusOrMinus 1e-6)
    }
})
