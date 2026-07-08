package io.github.shizukutanaka.popcoon.feature.calendar

import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.data.model.Platform
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

    /**
     * @param name 内部識別用の固定日本語文字列。SaleCalendarTest.kt が
     *   `.name.contains(...)` で判定に使うため保持する (テストは name の値自体を
     *   ユーザー表示に使わない限り安定した識別子として扱ってよい)。
     * @param nameRes UI 表示用のロケール対応文字列リソース (SaleCalendarScreen が使用)。
     *   以前は name をそのまま画面に表示しており EN/KO/ZH ロケールに日本語が漏れていた
     *   (商用リリース監査で発見)。
     * @param description 内部識別用の固定日本語文字列 (name と対称に保持)。
     * @param descRes UI 表示用のロケール対応文字列リソース。
     */
    data class Event(
        val name: String,
        val nameRes: Int,
        val platform: Platform?,    // null = 複数モール横断
        val startDate: LocalDate,
        val endDate: LocalDate,
        val tier: Tier,
        val description: String,
        val descRes: Int,
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
        // 当年だけでなく翌年分も候補に含める。
        // 12月後半は当年の大型セールが全て過去になるため、翌年の楽天スーパーセール春等を返す。
        return (seasonalSales(today) + seasonalSales(today.plusYears(1)))
            .filter { it.tier == Tier.MAJOR }
            .filter { it.startDate >= today }
            .filter { platform == null || it.platform == null || it.platform == platform }
            .minByOrNull { it.startDate }
    }

    /**
     * today より後に始まる大型・中型セールを startDate 昇順で返す (既定 120 日先まで)。
     *
     * セールカレンダー閲覧画面用。繰り返し日販促 (5のつく日など) は当日バナー側で扱うため
     * RECURRING は除外する。年境界 (12月→翌年春) は当年+翌年を候補にすることで担保する。
     */
    fun upcomingSales(
        today: LocalDate,
        withinDays: Int = 120,
        platform: Platform? = null,
    ): List<Event> {
        val horizon = today.plusDays(withinDays.toLong())
        return (seasonalSales(today) + seasonalSales(today.plusYears(1)))
            .filter { it.tier != Tier.RECURRING }
            .filter { it.startDate > today && it.startDate <= horizon }
            .filter { platform == null || it.platform == null || it.platform == platform }
            .distinctBy { it.name + it.startDate }
            .sortedBy { it.startDate }
    }

    /** 月内で発生する繰り返しセール (5のつく日など) */
    private fun monthlyRecurring(d: LocalDate): List<Event> {
        val events = mutableListOf<Event>()
        val day = d.dayOfMonth

        // Yahoo!ショッピング 5のつく日
        if (day == 5 || day == 15 || day == 25) {
            events += Event(
                name = "Yahoo! 5のつく日 +4%",
                nameRes = R.string.sale_yahoo_5_day_name,
                platform = Platform.YAHOO,
                startDate = d, endDate = d,
                tier = Tier.RECURRING,
                description = "PayPay ポイント追加4%",
                descRes = R.string.sale_yahoo_5_day_desc,
            )
        }

        // 楽天市場 5と0のつく日
        if (day in listOf(5, 10, 15, 20, 25, 30)) {
            events += Event(
                name = "楽天 5と0のつく日 +1%",
                nameRes = R.string.sale_rakuten_5_0_day_name,
                platform = Platform.RAKUTEN,
                startDate = d, endDate = d,
                tier = Tier.RECURRING,
                description = "エントリー必須、楽天カード利用で +1%",
                descRes = R.string.sale_rakuten_5_0_day_desc,
            )
        }

        // 日曜日の Yahoo!プレミアム特典
        if (d.dayOfWeek == DayOfWeek.SUNDAY) {
            events += Event(
                name = "Yahoo! 日曜日 +5%",
                nameRes = R.string.sale_yahoo_sunday_name,
                platform = Platform.YAHOO,
                startDate = d, endDate = d,
                tier = Tier.RECURRING,
                description = "PayPay ポイント+5%、要エントリー",
                descRes = R.string.sale_yahoo_sunday_desc,
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
                name = "楽天スーパーセール (春)", nameRes = R.string.sale_rakuten_super_spring_name,
                platform = Platform.RAKUTEN,
                startDate = LocalDate.of(year, 3, 4), endDate = LocalDate.of(year, 3, 11),
                tier = Tier.MAJOR,
                description = "半額商品 + ポイント最大44倍", descRes = R.string.sale_rakuten_super_desc,
            ),
            Event(
                name = "楽天スーパーセール (夏)", nameRes = R.string.sale_rakuten_super_summer_name,
                platform = Platform.RAKUTEN,
                startDate = LocalDate.of(year, 6, 4), endDate = LocalDate.of(year, 6, 11),
                tier = Tier.MAJOR,
                description = "半額商品 + ポイント最大44倍", descRes = R.string.sale_rakuten_super_desc,
            ),
            Event(
                name = "楽天スーパーセール (秋)", nameRes = R.string.sale_rakuten_super_autumn_name,
                platform = Platform.RAKUTEN,
                startDate = LocalDate.of(year, 9, 4), endDate = LocalDate.of(year, 9, 11),
                tier = Tier.MAJOR,
                description = "半額商品 + ポイント最大44倍", descRes = R.string.sale_rakuten_super_desc,
            ),
            Event(
                name = "楽天スーパーセール (冬)", nameRes = R.string.sale_rakuten_super_winter_name,
                platform = Platform.RAKUTEN,
                startDate = LocalDate.of(year, 12, 4), endDate = LocalDate.of(year, 12, 11),
                tier = Tier.MAJOR,
                description = "半額商品 + ポイント最大44倍", descRes = R.string.sale_rakuten_super_desc,
            ),

            // Amazon プライムデー (例年7月)
            Event(
                name = "Amazon プライムデー", nameRes = R.string.sale_amazon_prime_day_name,
                platform = Platform.AMAZON,
                startDate = LocalDate.of(year, 7, 16), endDate = LocalDate.of(year, 7, 17),
                tier = Tier.MAJOR,
                description = "プライム会員限定大幅値引き", descRes = R.string.sale_amazon_prime_day_desc,
            ),

            // Amazon ブラックフライデー
            Event(
                name = "Amazon ブラックフライデー", nameRes = R.string.sale_amazon_black_friday_name,
                platform = Platform.AMAZON,
                startDate = LocalDate.of(year, 11, 24), endDate = LocalDate.of(year, 12, 1),
                tier = Tier.MAJOR,
                description = "年末大型セール", descRes = R.string.sale_amazon_black_friday_desc,
            ),

            // Amazon サイバーマンデー
            Event(
                name = "Amazon サイバーマンデー", nameRes = R.string.sale_amazon_cyber_monday_name,
                platform = Platform.AMAZON,
                startDate = LocalDate.of(year, 12, 6), endDate = LocalDate.of(year, 12, 12),
                tier = Tier.MAJOR,
                description = "週末限定セール", descRes = R.string.sale_amazon_cyber_monday_desc,
            ),

            // Yahoo! 超 PayPay 祭り (不定期、参考)
            Event(
                name = "Yahoo! 超PayPay祭", nameRes = R.string.sale_yahoo_paypay_matsuri_name,
                platform = Platform.YAHOO,
                startDate = LocalDate.of(year, 11, 1), endDate = LocalDate.of(year, 11, 30),
                tier = Tier.MEDIUM,
                description = "11月恒例の大型キャンペーン", descRes = R.string.sale_yahoo_paypay_matsuri_desc,
            ),
        )
    }
}
