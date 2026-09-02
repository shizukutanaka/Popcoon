package io.github.shizukutanaka.popcoon.widget

// PopcoonWidget.kt (Glance) から切り出した純ロジック。同居していると Glance 依存に
// 巻き込まれて実コンパイルも kotest (WidgetSaleLogicTest) も走らなかった。
// 同一パッケージなので呼び出し側 (PopcoonWidget.loadTodayInfo) は無変更。

/**
 * ウィジェットの「今日のセール情報」判定 (純関数、Context 非依存)。
 * 単体テストで網羅検証する。
 *
 * i18n: 表示文字列は持たず **どのセールか (SaleKind)** だけを返す。ラベルの
 * ロケール解決は [PopcoonWidget.loadTodayInfo] が string resource で行う。
 */
internal object PopcoonWidgetLogic {

    enum class SaleKind { YAHOO_5DAY, RAKUTEN_50DAY, YAHOO_SUNDAY, NEXT }

    /** @param nextDay NEXT のときのみ意味を持つ (次回ポイントアップ日)。 */
    data class SaleInfo(val kind: SaleKind, val isActive: Boolean, val nextDay: Int = 0)

    fun todayInfo(day: Int, dow: java.time.DayOfWeek): SaleInfo = when {
        // Yahoo! 5のつく日 (5/15/25) — 共有日は高還元の Yahoo を優先表示。
        day == 5 || day == 15 || day == 25 ->
            SaleInfo(SaleKind.YAHOO_5DAY, isActive = true)
        // 楽天 5と0のつく日のうち Yahoo と重ならない日 (10/20/30)。
        // (5/15/25 は上で Yahoo に振るので、ここに 5 を入れると到達不能=デッドになる)
        day == 10 || day == 20 || day == 30 ->
            SaleInfo(SaleKind.RAKUTEN_50DAY, isActive = true)
        dow == java.time.DayOfWeek.SUNDAY ->
            SaleInfo(SaleKind.YAHOO_SUNDAY, isActive = true)
        else ->
            SaleInfo(SaleKind.NEXT, isActive = false, nextDay = nextPointDay(day))
    }

    /** 今日以降で次にポイントアップする日 (5と0のつく日)。月末を越えると翌月の 5。 */
    fun nextPointDay(today: Int): Int {
        val candidates = listOf(5, 10, 15, 20, 25, 30)
        return candidates.firstOrNull { it > today } ?: 5
    }
}
