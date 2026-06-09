package com.example.popcoon.widget

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * ウィジェットのセール判定ロジックテスト。
 *
 * Glance UI 部分は Instrumentation テストに委ね、ここでは日付→セール情報の
 * pure 計算 (PopcoonWidgetLogic) を **本番コードを直接呼んで** 検証する。
 * (以前は本番ロジックをテスト内に再実装しており、回帰を検出できなかった)
 */
class WidgetSaleLogicTest : StringSpec({

    fun info(date: LocalDate) = PopcoonWidgetLogic.todayInfo(date.dayOfMonth, date.dayOfWeek)

    "5日: Yahoo 5のつく日" {
        val r = info(LocalDate.of(2026, 5, 5))
        r.label shouldBe "Yahoo! 5のつく日 +4%"
        r.isActive shouldBe true
    }

    "15日・25日も Yahoo 5のつく日" {
        info(LocalDate.of(2026, 5, 15)).label shouldBe "Yahoo! 5のつく日 +4%"
        info(LocalDate.of(2026, 5, 25)).label shouldBe "Yahoo! 5のつく日 +4%"
    }

    "10日・20日・30日: 楽天 5と0のつく日" {
        info(LocalDate.of(2026, 5, 10)).label shouldBe "楽天 5と0のつく日 +1%"
        info(LocalDate.of(2026, 5, 20)).label shouldBe "楽天 5と0のつく日 +1%"
        info(LocalDate.of(2026, 4, 30)).label shouldBe "楽天 5と0のつく日 +1%"
    }

    "日曜日: Yahoo 日曜 +5% (5と0のつく日でない日)" {
        val date = LocalDate.of(2026, 5, 3)  // 日曜
        date.dayOfWeek shouldBe DayOfWeek.SUNDAY
        val r = info(date)
        r.label shouldBe "Yahoo! 日曜日 +5%"
        r.isActive shouldBe true
    }

    "1日 (非日曜): 次回 = 5日" {
        val date = LocalDate.of(2026, 6, 1)
        if (date.dayOfWeek != DayOfWeek.SUNDAY) {
            val r = info(date)
            r.isActive shouldBe false
            r.label shouldBe "次回: 5日 ポイントUP"
        }
    }

    "26日 (非日曜): 次回 = 30日" {
        val date = LocalDate.of(2026, 5, 26)
        if (date.dayOfWeek != DayOfWeek.SUNDAY) {
            info(date).label shouldBe "次回: 30日 ポイントUP"
        }
    }

    "nextPointDay: 月末超えは翌月 5 に回る" {
        PopcoonWidgetLogic.nextPointDay(31) shouldBe 5
        PopcoonWidgetLogic.nextPointDay(30) shouldBe 5
        PopcoonWidgetLogic.nextPointDay(7) shouldBe 10
    }
})
