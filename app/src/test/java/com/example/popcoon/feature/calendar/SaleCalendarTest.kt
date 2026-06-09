package com.example.popcoon.feature.calendar

import com.example.popcoon.data.model.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import java.time.LocalDate

class SaleCalendarTest : StringSpec({

    "5のつく日に Yahoo セールが発火" {
        val d = LocalDate.of(2026, 4, 5)
        val sales = SaleCalendar.activeSales(d, Platform.YAHOO)
        sales.any { it.name.contains("5のつく日") } shouldBe true
    }

    "10日に楽天 5と0のつく日が発火、Yahoo は発火せず" {
        val d = LocalDate.of(2026, 4, 10)
        val rakuten = SaleCalendar.activeSales(d, Platform.RAKUTEN)
        rakuten.any { it.name.contains("5と0") } shouldBe true

        val yahoo = SaleCalendar.activeSales(d, Platform.YAHOO)
        yahoo.any { it.name.contains("5のつく日") } shouldBe false
    }

    "1日 (5/0でも日曜でもない) はリカーリングなし" {
        val d = LocalDate.of(2026, 4, 1)  // 水曜
        SaleCalendar.activeSales(d).filter {
            it.tier == SaleCalendar.Tier.RECURRING
        }.size shouldBe 0
    }

    "日曜は Yahoo +5% が発火" {
        val sunday = LocalDate.of(2026, 4, 12)
        sunday.dayOfWeek shouldBe java.time.DayOfWeek.SUNDAY
        val sales = SaleCalendar.activeSales(sunday, Platform.YAHOO)
        sales.any { it.name.contains("日曜日") } shouldBe true
    }

    "次の楽天スーパーセール検索" {
        val ref = LocalDate.of(2026, 4, 1)
        val next = SaleCalendar.nextMajorSale(ref, Platform.RAKUTEN)
        next.shouldNotBeNull()
        next.name.contains("楽天スーパーセール") shouldBe true
        // 6月のセールが一番近い
        next.startDate shouldBe LocalDate.of(2026, 6, 4)
    }

    "プライムデー期間中の検索" {
        val d = LocalDate.of(2026, 7, 16)
        val sales = SaleCalendar.activeSales(d, Platform.AMAZON)
        sales.any { it.name.contains("プライムデー") } shouldBe true
    }

    "活性セールリストは tier 降順" {
        val d = LocalDate.of(2026, 7, 17)  // プライムデー中 + 多数の繰り返し
        val sales = SaleCalendar.activeSales(d)
        // MAJOR が先頭
        if (sales.isNotEmpty()) {
            sales.first().tier shouldBe SaleCalendar.Tier.MAJOR
        }
    }

    // 年境界回帰: 12月後半は当年の大型セールが全て過去 → 翌年春を返すべき
    "12月後半でも翌年の大型セールを返す (年境界)" {
        val d = LocalDate.of(2026, 12, 20)  // サイバーマンデー(12/6)も過ぎている
        val next = SaleCalendar.nextMajorSale(d)
        next.shouldNotBeNull()
        // 翌年 (2027) 3月の楽天スーパーセール春が最も近い
        next.startDate shouldBe LocalDate.of(2027, 3, 4)
    }

    "大晦日でも null を返さない" {
        SaleCalendar.nextMajorSale(LocalDate.of(2026, 12, 31)).shouldNotBeNull()
    }
})
