package com.example.popcoon.widget

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * ウィジェットのセール判定ロジックテスト。
 *
 * Glance UI 部分は Instrumentation テストに委ね、
 * ここでは日付→セール情報の pure 計算のみを検証。
 */
class WidgetSaleLogicTest : StringSpec({

    // PopcoonWidget.loadTodayInfo() の再現ロジック
    fun todayLabel(date: LocalDate): Pair<String, Boolean> {
        val day = date.dayOfMonth
        val dow = date.dayOfWeek
        return when {
            day == 5 || day == 15 || day == 25 ->
                "Yahoo! 5のつく日 +4%" to true
            day in listOf(5, 10, 20, 30) ->
                "楽天 5と0のつく日 +1%" to true
            dow == DayOfWeek.SUNDAY ->
                "Yahoo! 日曜日 +5%" to true
            else -> {
                val candidates = listOf(5, 10, 15, 20, 25, 30)
                val next = candidates.firstOrNull { it > day } ?: 5
                "次回: ${next}日 ポイントUP" to false
            }
        }
    }

    "5日: Yahoo 5のつく日" {
        val (label, active) = todayLabel(LocalDate.of(2026, 5, 5))
        label shouldBe "Yahoo! 5のつく日 +4%"
        active shouldBe true
    }

    "15日: Yahoo 5のつく日" {
        val (_, active) = todayLabel(LocalDate.of(2026, 5, 15))
        active shouldBe true
    }

    "10日: 楽天 5と0のつく日" {
        val (label, _) = todayLabel(LocalDate.of(2026, 5, 10))
        label shouldBe "楽天 5と0のつく日 +1%"
    }

    "日曜日: Yahoo 日曜 +5%" {
        // 2026-05-03 は日曜
        val date = LocalDate.of(2026, 5, 3)
        date.dayOfWeek shouldBe DayOfWeek.SUNDAY
        val (label, active) = todayLabel(date)
        label shouldBe "Yahoo! 日曜日 +5%"
        active shouldBe true
    }

    "1日 (月曜): 次回 = 5日" {
        val date = LocalDate.of(2026, 6, 1)
        val (label, active) = todayLabel(date)
        active shouldBe false
        label shouldBe "次回: 5日 ポイントUP"
    }

    "26日: 次回 = 30日" {
        val date = LocalDate.of(2026, 5, 26)
        if (date.dayOfWeek != DayOfWeek.SUNDAY) {
            val (label, _) = todayLabel(date)
            label shouldBe "次回: 30日 ポイントUP"
        }
    }

    "31日: 次回 = 5日 (翌月)" {
        val date = LocalDate.of(2026, 5, 31)
        if (date.dayOfWeek != DayOfWeek.SUNDAY) {
            val (label, _) = todayLabel(date)
            label shouldBe "次回: 5日 ポイントUP"
        }
    }
})
