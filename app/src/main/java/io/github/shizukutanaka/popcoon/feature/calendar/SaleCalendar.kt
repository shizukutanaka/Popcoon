package io.github.shizukutanaka.popcoon.feature.calendar

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
 *
 * このファイルは popcoon-tdd/kotlin_parity/run.sh が Android SDK 無しで直接コンパイルする
 * 対象 (BuyTimingScorer.kt が signalUpcomingSale() 経由でこのファイルに依存するため)。
 * よって Android リソース (R) への依存は一切持ち込まない — 過去に一度 `import R` を
 * 追加してこのハーネスを壊した (この場ではビルド実行できず検出できなかった) ため、
 * ローカライズ済み文字列へのマッピングは ui/SaleCalendarLabels.kt (Android 依存があってよい
 * UI 層) に置き、ここでは `kind` という安定した enum 識別子だけを持たせる。
 */
object SaleCalendar {

    enum class Tier { MAJOR, MEDIUM, RECURRING }

    /** UI 層 (ui/SaleCalendarLabels.kt) が文字列リソースへマッピングする際の安定識別子。 */
    enum class Kind {
        YAHOO_5_DAY, RAKUTEN_5_0_DAY, YAHOO_SUNDAY, YAHOO_KANSHA_DAY,
        RAKUTEN_SUPER_SPRING, RAKUTEN_SUPER_SUMMER, RAKUTEN_SUPER_AUTUMN, RAKUTEN_SUPER_WINTER,
        AMAZON_PRIME_DAY, AMAZON_BLACK_FRIDAY, AMAZON_CYBER_MONDAY, YAHOO_PAYPAY_MATSURI,
    }

    /**
     * @param name 内部識別用の固定日本語文字列。SaleCalendarTest.kt が
     *   `.name.contains(...)` で判定に使うため保持する。
     * @param kind UI 表示用ローカライズ文字列を引くための安定識別子
     *   (ui/SaleCalendarLabels.kt の `Event.nameRes()`/`descRes()` が使用)。
     * @param description 内部識別用の固定日本語文字列 (name と対称に保持)。
     */
    data class Event(
        val name: String,
        val kind: Kind,
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
                kind = Kind.YAHOO_5_DAY,
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
                kind = Kind.RAKUTEN_5_0_DAY,
                platform = Platform.RAKUTEN,
                startDate = d, endDate = d,
                tier = Tier.RECURRING,
                description = "エントリー必須、楽天カード利用で +1%",
            )
        }

        // プレミアムな日曜日 (2026-07 リサーチで条件を確認: LYPプレミアム/ソフトバンク
        // 会員限定 + 1注文 5,000 円以上。旧ソフトバンク日曜特典は 2022-10 終了)
        if (d.dayOfWeek == DayOfWeek.SUNDAY) {
            events += Event(
                name = "Yahoo! 日曜日 +5%",
                kind = Kind.YAHOO_SUNDAY,
                platform = Platform.YAHOO,
                startDate = d, endDate = d,
                tier = Tier.RECURRING,
                description = "LYPプレミアム/ソフトバンク会員限定、1注文5,000円以上",
            )
        }

        // ヤフショ感謝デー (2025-10 にゾロ目の日を置き換えて開始。11日・22日、
        // 会員ランク条件あり: シルバー+4%/ゴールド+5%。2026-07 リサーチで確認)
        if (day == 11 || day == 22) {
            events += Event(
                name = "ヤフショ感謝デー +4〜5%",
                kind = Kind.YAHOO_KANSHA_DAY,
                platform = Platform.YAHOO,
                startDate = d, endDate = d,
                tier = Tier.RECURRING,
                description = "会員ランク条件あり (シルバー+4%/ゴールド+5%)、獲得は期間限定PayPayポイント",
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
                name = "楽天スーパーセール (春)", kind = Kind.RAKUTEN_SUPER_SPRING,
                platform = Platform.RAKUTEN,
                startDate = LocalDate.of(year, 3, 4), endDate = LocalDate.of(year, 3, 11),
                tier = Tier.MAJOR,
                description = "半額商品 + ポイント最大44倍",
            ),
            Event(
                name = "楽天スーパーセール (夏)", kind = Kind.RAKUTEN_SUPER_SUMMER,
                platform = Platform.RAKUTEN,
                startDate = LocalDate.of(year, 6, 4), endDate = LocalDate.of(year, 6, 11),
                tier = Tier.MAJOR,
                description = "半額商品 + ポイント最大44倍",
            ),
            Event(
                name = "楽天スーパーセール (秋)", kind = Kind.RAKUTEN_SUPER_AUTUMN,
                platform = Platform.RAKUTEN,
                startDate = LocalDate.of(year, 9, 4), endDate = LocalDate.of(year, 9, 11),
                tier = Tier.MAJOR,
                description = "半額商品 + ポイント最大44倍",
            ),
            Event(
                name = "楽天スーパーセール (冬)", kind = Kind.RAKUTEN_SUPER_WINTER,
                platform = Platform.RAKUTEN,
                startDate = LocalDate.of(year, 12, 4), endDate = LocalDate.of(year, 12, 11),
                tier = Tier.MAJOR,
                description = "半額商品 + ポイント最大44倍",
            ),

            // Amazon プライムデー (例年7月)
            Event(
                name = "Amazon プライムデー", kind = Kind.AMAZON_PRIME_DAY,
                platform = Platform.AMAZON,
                startDate = LocalDate.of(year, 7, 16), endDate = LocalDate.of(year, 7, 17),
                tier = Tier.MAJOR,
                description = "プライム会員限定大幅値引き",
            ),

            // Amazon ブラックフライデー
            Event(
                name = "Amazon ブラックフライデー", kind = Kind.AMAZON_BLACK_FRIDAY,
                platform = Platform.AMAZON,
                startDate = LocalDate.of(year, 11, 24), endDate = LocalDate.of(year, 12, 1),
                tier = Tier.MAJOR,
                description = "年末大型セール",
            ),

            // Amazon サイバーマンデー
            Event(
                name = "Amazon サイバーマンデー", kind = Kind.AMAZON_CYBER_MONDAY,
                platform = Platform.AMAZON,
                startDate = LocalDate.of(year, 12, 6), endDate = LocalDate.of(year, 12, 12),
                tier = Tier.MAJOR,
                description = "週末限定セール",
            ),

            // Yahoo! 超 PayPay 祭り (不定期、参考)
            Event(
                name = "Yahoo! 超PayPay祭", kind = Kind.YAHOO_PAYPAY_MATSURI,
                platform = Platform.YAHOO,
                startDate = LocalDate.of(year, 11, 1), endDate = LocalDate.of(year, 11, 30),
                tier = Tier.MEDIUM,
                description = "11月恒例の大型キャンペーン",
            ),
        )
    }
}
