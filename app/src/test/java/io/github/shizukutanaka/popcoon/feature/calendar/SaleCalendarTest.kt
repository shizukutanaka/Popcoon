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

    // 2025-10 にゾロ目の日 (11/22) が「ヤフショ感謝デー」に置き換わった (2026-07 リサーチ)。
    "11日・22日はヤフショ感謝デーが発火" {
        for (day in listOf(11, 22)) {
            val d = LocalDate.of(2026, 5, day)
            val sales = SaleCalendar.activeSales(d, Platform.YAHOO)
            sales.shouldExist { it.kind == SaleCalendar.Kind.YAHOO_KANSHA_DAY }
        }
    }

    "12日は感謝デーが発火しない" {
        val d = LocalDate.of(2026, 5, 12)
        SaleCalendar.activeSales(d, Platform.YAHOO)
            .shouldNotExist { it.kind == SaleCalendar.Kind.YAHOO_KANSHA_DAY }
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

    // 回帰: この検査は元々 2026-07-17 (金曜・17 日) を使っており、
    // 「プライムデー中 + 多数の繰り返し」というコメントに反して monthlyRecurring が
    // 1 件も返さない日だった (5のつく日でも 5と0でも 11/22 でも日曜でもない)。
    // 活性セールがプライムデー 1 件だけなので `first().tier == MAJOR` は並び順と
    // 無関係に成立し、実装が `sortedByDescending { it.tier.ordinal }` で
    // 真逆に並んでいたことを検出できなかった。両 tier が同時に活性な日へ差し替え、
    // フィクスチャが本当に条件を踏んでいることも表明する。
    // 全 365 日の実行検証は kotlin_parity/run_calendar.sh。
    "活性セールリストは重要度の高い順 (MAJOR → MEDIUM → RECURRING)" {
        // 2026-12-06 は日曜。楽天スーパーセール冬 (12/4-11) と
        // サイバーマンデー (12/6-12) が MAJOR、Yahoo! 日曜 +5% が RECURRING。
        val d = LocalDate.of(2026, 12, 6)
        val sales = SaleCalendar.activeSales(d)

        // フィクスチャ自体の有効性 — 両 tier が実際に含まれること
        sales.count { it.tier == SaleCalendar.Tier.MAJOR } shouldBe 2
        sales.any { it.tier == SaleCalendar.Tier.RECURRING } shouldBe true

        sales.first().tier shouldBe SaleCalendar.Tier.MAJOR
        val ordinals = sales.map { it.tier.ordinal }
        ordinals shouldBe ordinals.sorted()
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
