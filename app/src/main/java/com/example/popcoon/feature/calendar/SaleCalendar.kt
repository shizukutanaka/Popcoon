package com.example.popcoon.feature.calendar

import com.example.popcoon.data.model.Platform
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * EC 各社のセールカレンダー。
 *
 * 同種ソフト調査:
 *  - Pricey: セールカレンダー機能で各 EC のセール期間を事前確認可能 (主要差別化)
 *  - 「いつセールが来るか」を把握 → 安いタイミングで購入
 *
 * Popcoon 実装方針:
 *  - 月次の繰り返しセール (5のつく日など) は計算で導出
 *  - 大型セール (Prime Day, ブラックフライデー, 楽天スーパーセール) は手動データ
 *  - 月初に backend からセール情報を取得して上書き可能
 */
object SaleCalendar {

    enum class Tier { MAJOR, MEDIUM, RECURRING }

    data class Event(
        val name: String,
        val platform: Platform?,    // null = 複数モール横断
        val startDate: LocalDate,
        val endDate: LocalDate,
        val tier: Tier,
        val description: String,
    )

    /** ある日 (today) が今アクティブなセール一覧 */
    fun activeSales(date: LocalDate, platform: Platform? = null): List<Event> {
        val events = mutableListOf<Event>()
        events += monthlyRecurring(date)
        events += seasonalSales(date)
        return events.filter { it.startDate <= date && date <= it.endDate }
            .filter { platform == null || it.platform == null || it.platform == platform }
            .sortedByDescending { it.tier.ordinal }
    }

    /** 次に来る大型セール (今日以降で最も近いもの) */
    fun nextMajorSale(today: LocalDate, platform: Platform? = null): Event? {
        return seasonalSales(today)
            .filter { it.tier == Tier.MAJOR }
            .filter { it.startDate >= today }
            .filter { platform == null || it.platform == null || it.platform == platform }
            .minByOrNull { it.startDate }
    }

    /** 月内で発生する繰り返しセール (5のつく日など) */
    private fun monthlyRecurring(d: LocalDate): List<Event> {
        val events = mutableListOf<Event>()
        val day = d.dayOfMonth

        // Yahoo!ショッピング 5のつく日
        if (day == 5 || day == 15 || day == 25) {
            events += Event(
                name = "Yahoo! 5のつく日 +4%",
                platform = Platform.YAHOO,
                startDate = d, endDate = d,
                tier = Tier.RECURRING,
                description = "PayPay ポイント追加4%",
            )
        }

        // 楽天市場 5と0のつく日
        if (day in listOf(5, 10, 15, 20, 25, 30)) {
            events += Event(
                name = "楽天 5と0のつく日 +1%",
                platform = Platform.RAKUTEN,
                startDate = d, endDate = d,
                tier = Tier.RECURRING,
                description = "エントリー必須、楽天カード利用で +1%",
            )
        }

        // 日曜日の Yahoo!プレミアム特典
        if (d.dayOfWeek == DayOfWeek.SUNDAY) {
            events += Event(
                name = "Yahoo! 日曜日 +5%",
                platform = Platform.YAHOO,
                startDate = d, endDate = d,
                tier = Tier.RECURRING,
                description = "PayPay ポイント+5%、要エントリー",
            )
        }

        return events
    }

    /**
     * 大型セール (年単位の手動データ)。
     * 完全網羅は backend cron で fetch する方針、ここは offline fallback。
     */
    private fun seasonalSales(referenceDate: LocalDate): List<Event> {
        val year = referenceDate.year
        return listOf(
            // 楽天スーパーセール (3, 6, 9, 12月の頭)
            Event(
                "楽天スーパーセール (春)", Platform.RAKUTEN,
                LocalDate.of(year, 3, 4), LocalDate.of(year, 3, 11),
                Tier.MAJOR,
                "半額商品 + ポイント最大44倍",
            ),
            Event(
                "楽天スーパーセール (夏)", Platform.RAKUTEN,
                LocalDate.of(year, 6, 4), LocalDate.of(year, 6, 11),
                Tier.MAJOR, "半額商品 + ポイント最大44倍",
            ),
            Event(
                "楽天スーパーセール (秋)", Platform.RAKUTEN,
                LocalDate.of(year, 9, 4), LocalDate.of(year, 9, 11),
                Tier.MAJOR, "半額商品 + ポイント最大44倍",
            ),
            Event(
                "楽天スーパーセール (冬)", Platform.RAKUTEN,
                LocalDate.of(year, 12, 4), LocalDate.of(year, 12, 11),
                Tier.MAJOR, "半額商品 + ポイント最大44倍",
            ),

            // Amazon プライムデー (例年7月)
            Event(
                "Amazon プライムデー", Platform.AMAZON,
                LocalDate.of(year, 7, 16), LocalDate.of(year, 7, 17),
                Tier.MAJOR, "プライム会員限定大幅値引き",
            ),

            // Amazon ブラックフライデー
            Event(
                "Amazon ブラックフライデー", Platform.AMAZON,
                LocalDate.of(year, 11, 24), LocalDate.of(year, 12, 1),
                Tier.MAJOR, "年末大型セール",
            ),

            // Amazon サイバーマンデー
            Event(
                "Amazon サイバーマンデー", Platform.AMAZON,
                LocalDate.of(year, 12, 6), LocalDate.of(year, 12, 12),
                Tier.MAJOR, "週末限定セール",
            ),

            // Yahoo! 超 PayPay 祭り (不定期、参考)
            Event(
                "Yahoo! 超PayPay祭", Platform.YAHOO,
                LocalDate.of(year, 11, 1), LocalDate.of(year, 11, 30),
                Tier.MEDIUM, "11月恒例の大型キャンペーン",
            ),
        )
    }
}
