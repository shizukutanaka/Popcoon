package io.github.shizukutanaka.popcoon.ui

import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternDetector

/**
 * [DarkPatternDetector.Warning] → ローカライズ済み文字列リソース ID + フォーマット引数。
 *
 * `Warning.label` は日本語固定文字列で、以前は検索結果・商品詳細画面の警告表示に
 * そのまま使われ EN/KO/ZH ロケールに日本語が漏れていた (商用リリース監査で発見)。
 * DarkPatternDetector 自体は popcoon-tdd (Python オラクル) と 1:1 対応する純粋ロジックの
 * ままにするため Android/リソース依存を持ち込まず、このマッピングを UI 層に置く。
 *
 * DRIP_PRICING は同一 WarningType に閾値違いの2文言 (severity で区別) があるため
 * severity も条件に含める。
 */
fun DarkPatternDetector.Warning.toLabelResource(): Pair<Int, List<Any>> = when (type) {
    DarkPatternDetector.WarningType.ALWAYS_ON_DISCOUNT ->
        R.string.dp_always_on_discount to emptyList()
    DarkPatternDetector.WarningType.INFLATED_LIST_PRICE ->
        R.string.dp_inflated_list_price to emptyList()
    DarkPatternDetector.WarningType.PRE_SALE_MARKUP ->
        R.string.dp_pre_sale_markup to emptyList()
    DarkPatternDetector.WarningType.CHARM_PRICING ->
        R.string.dp_charm_pricing to emptyList()
    DarkPatternDetector.WarningType.FAKE_SCARCITY ->
        R.string.dp_fake_scarcity to emptyList()
    DarkPatternDetector.WarningType.COUNTDOWN_MANIPULATION ->
        R.string.dp_countdown_manipulation to emptyList()
    DarkPatternDetector.WarningType.DRIP_PRICING -> {
        val resId = if (severity == DarkPatternDetector.Severity.HIGH) {
            R.string.dp_drip_pricing_high
        } else {
            R.string.dp_drip_pricing_medium
        }
        resId to labelArgs
    }
}
