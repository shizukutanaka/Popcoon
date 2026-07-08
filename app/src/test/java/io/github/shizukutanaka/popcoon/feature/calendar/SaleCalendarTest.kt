package io.github.shizukutanaka.popcoon.feature.calendar

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldNotExist
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalDate

class SaleCalendarTest : StringSpec({

    "5のつく日に Yahoo セールが発火" {
        val d = LocalDate.of(2026, 4, 5)
        val sales = SaleCalendar.activeSales(d, Platform.YAHOO)
        sales.shouldExist { it.name.contains("5のつく日") }
    }

    "10日に楽天 5と0のつく日が発火、Yahoo は発火せず" {
        val d = LocalDate.of(2026, 4, 10)
        val rakuten = SaleCalendar.activeSales(d, Platform.RAKUTEN)
        rakuten.shouldExist { it.name.contains("5と0") }

        val yahoo = SaleCalendar.activeSales(d, Platform.YAHOO)
        yahoo.shouldNotExist { it.name.contains("5のつく日") }
    }

    "1日 (5/0でも日曜でもない) はリカーリングなし" {
        val d = LocalDate.of(2026, 4, 1)  // 水曜
        SaleCalendar.activeSales(d)
            .shouldNotExist { it.tier == SaleCalendar.Tier.RECURRING }
    }

    "日曜は Yahoo +5% が発火" {
        val sunday = LocalDate.of(2026, 4, 12)
        sunday.dayOfWeek shouldBe java.time.DayOfWeek.SUNDAY
        val sales = SaleCalendar.activeSales(sunday, Platform.YAHOO)
        sales.shouldExist { it.name.contains("日曜日") }
    }

    "次の楽天スーパーセール検索" {
        val ref = LocalDate.of(2026, 4, 1)
        val next = SaleCalendar.nextMajorSale(ref, Platform.RAKUTEN)
        next.shouldNotBeNull()
        next.name shouldContain "楽天スーパーセール"
        // 6月のセールが一番近い
        next.startDate shouldBe LocalDate.of(2026, 6, 4)
    }

    "プライムデー期間中の検索" {
        val d = LocalDate.of(2026, 7, 16)
        val sales = SaleCalendar.activeSales(d, Platform.AMAZON)
        sales.shouldExist { it.name.contains("プライムデー") }
    }

    "活性セールリストは tier 降順" {
        val d = LocalDate.of(2026, 7, 17)  // プライムデー中 + 多数の繰り返し
        val sales = SaleCalendar.activeSales(d)
        // MAJOR が先頭
        sales.shouldNotBeEmpty()
        sales.first().tier shouldBe SaleCalendar.Tier.MAJOR
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

    // ── upcomingSales (閲覧画面用) ────────────────────────────────────────────
    "upcoming: 4月時点で6月の楽天スーパーセール(MAJOR)を含み、当日RECURRINGは含まない" {
        val d = LocalDate.of(2026, 4, 10)  // 楽天「5と0のつく日」(RECURRING) が当日活性
        val upcoming = SaleCalendar.upcomingSales(d)
        upcoming.shouldExist { it.name.contains("楽天スーパーセール") }
        upcoming.map { it.tier } shouldNotContain SaleCalendar.Tier.RECURRING
    }

    "upcoming: startDate 昇順" {
        val d = LocalDate.of(2026, 4, 1)
        val dates = SaleCalendar.upcomingSales(d).map { it.startDate }
        dates shouldBe dates.sortedBy { it }
    }

    "upcoming: withinDays の窓で遠方の大型セールを除外" {
        val d = LocalDate.of(2026, 4, 1)
        // 7日窓: 直近1週間に大型セールは無いので空
        SaleCalendar.upcomingSales(d, withinDays = 7).shouldBeEmpty()
        // 120日窓: 6月の楽天スーパーセールが入る
        SaleCalendar.upcomingSales(d, withinDays = 120).shouldNotBeEmpty()
    }

    "upcoming: 12月後半でも翌年春の大型セールを返す (年境界)" {
        val d = LocalDate.of(2026, 12, 20)
        val upcoming = SaleCalendar.upcomingSales(d, withinDays = 120)
        upcoming.shouldNotBeEmpty()
        // 最も近いのは翌年3月の楽天スーパーセール春
        upcoming.first().startDate shouldBe LocalDate.of(2027, 3, 4)
    }

    "upcoming: platform 指定で絞り込める" {
        val d = LocalDate.of(2026, 4, 1)
        val amazon = SaleCalendar.upcomingSales(d, platform = Platform.AMAZON)
        amazon.shouldNotExist { it.platform != null && it.platform != Platform.AMAZON }
    }
})
