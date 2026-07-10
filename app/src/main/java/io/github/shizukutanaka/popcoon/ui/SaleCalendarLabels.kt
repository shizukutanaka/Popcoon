package io.github.shizukutanaka.popcoon.ui

import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.feature.calendar.SaleCalendar

/**
 * [SaleCalendar.Event] → ローカライズ済み文字列リソース ID。
 *
 * SaleCalendar.kt は popcoon-tdd/kotlin_parity/run.sh が Android SDK 無しで直接
 * コンパイルする対象 (BuyTimingScorer.kt 経由の依存) のため Android/R 依存を持ち込めない
 * (過去に一度持ち込んでこのハーネスを壊した)。このマッピングはその制約が無い UI 層に置く
 * — ui/DarkPatternLabels.kt と同じ方針。
 */
fun SaleCalendar.Event.nameRes(): Int = when (kind) {
    SaleCalendar.Kind.YAHOO_5_DAY -> R.string.sale_yahoo_5_day_name
    SaleCalendar.Kind.RAKUTEN_5_0_DAY -> R.string.sale_rakuten_5_0_day_name
    SaleCalendar.Kind.YAHOO_SUNDAY -> R.string.sale_yahoo_sunday_name
    SaleCalendar.Kind.RAKUTEN_SUPER_SPRING -> R.string.sale_rakuten_super_spring_name
    SaleCalendar.Kind.RAKUTEN_SUPER_SUMMER -> R.string.sale_rakuten_super_summer_name
    SaleCalendar.Kind.RAKUTEN_SUPER_AUTUMN -> R.string.sale_rakuten_super_autumn_name
    SaleCalendar.Kind.RAKUTEN_SUPER_WINTER -> R.string.sale_rakuten_super_winter_name
    SaleCalendar.Kind.AMAZON_PRIME_DAY -> R.string.sale_amazon_prime_day_name
    SaleCalendar.Kind.AMAZON_BLACK_FRIDAY -> R.string.sale_amazon_black_friday_name
    SaleCalendar.Kind.AMAZON_CYBER_MONDAY -> R.string.sale_amazon_cyber_monday_name
    SaleCalendar.Kind.YAHOO_PAYPAY_MATSURI -> R.string.sale_yahoo_paypay_matsuri_name
}

fun SaleCalendar.Event.descRes(): Int = when (kind) {
    SaleCalendar.Kind.YAHOO_5_DAY -> R.string.sale_yahoo_5_day_desc
    SaleCalendar.Kind.RAKUTEN_5_0_DAY -> R.string.sale_rakuten_5_0_day_desc
    SaleCalendar.Kind.YAHOO_SUNDAY -> R.string.sale_yahoo_sunday_desc
    SaleCalendar.Kind.RAKUTEN_SUPER_SPRING,
    SaleCalendar.Kind.RAKUTEN_SUPER_SUMMER,
    SaleCalendar.Kind.RAKUTEN_SUPER_AUTUMN,
    SaleCalendar.Kind.RAKUTEN_SUPER_WINTER -> R.string.sale_rakuten_super_desc
    SaleCalendar.Kind.AMAZON_PRIME_DAY -> R.string.sale_amazon_prime_day_desc
    SaleCalendar.Kind.AMAZON_BLACK_FRIDAY -> R.string.sale_amazon_black_friday_desc
    SaleCalendar.Kind.AMAZON_CYBER_MONDAY -> R.string.sale_amazon_cyber_monday_desc
    SaleCalendar.Kind.YAHOO_PAYPAY_MATSURI -> R.string.sale_yahoo_paypay_matsuri_desc
}
