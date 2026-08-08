package io.github.shizukutanaka.popcoon.ui

import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternDetector
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternTextDetector

/**
 * [DarkPatternTextDetector.Category] → ローカライズ済みカテゴリ名の文字列リソース ID。
 *
 * 以前は検出したカテゴリ名自体が UI に一切表示されず (検出根拠の生テキストと
 * `severity.name`＝英語 enum 識別子のみ)、ユーザーはどの種類のダークパターンが
 * 検出されたのか判別できなかった (機能過不足監査で発見)。
 */
fun DarkPatternTextDetector.Category.toLabelResource(): Int = when (this) {
    DarkPatternTextDetector.Category.URGENCY -> R.string.dp_text_category_urgency
    DarkPatternTextDetector.Category.SCARCITY -> R.string.dp_text_category_scarcity
    DarkPatternTextDetector.Category.SOCIAL_PROOF -> R.string.dp_text_category_social_proof
    DarkPatternTextDetector.Category.MISDIRECTION -> R.string.dp_text_category_misdirection
    DarkPatternTextDetector.Category.FORCED_ACTION -> R.string.dp_text_category_forced_action
    DarkPatternTextDetector.Category.HIDDEN_SUBSCRIPTION -> R.string.dp_text_category_hidden_subscription
    DarkPatternTextDetector.Category.OBSTRUCTION -> R.string.dp_text_category_obstruction
}

/**
 * [DarkPatternTextDetector.Severity] → ローカライズ済み深刻度名の文字列リソース ID。
 * 以前は `severity.name` (LOW/MEDIUM/HIGH の生の英語識別子) がロケールを問わず
 * そのまま表示されていた。
 */
fun DarkPatternTextDetector.Severity.toLabelResource(): Int = when (this) {
    DarkPatternTextDetector.Severity.LOW -> R.string.severity_low
    DarkPatternTextDetector.Severity.MEDIUM -> R.string.severity_medium
    DarkPatternTextDetector.Severity.HIGH -> R.string.severity_high
}

/**
 * [DarkPatternDetector.Severity] → ローカライズ済み深刻度名の文字列リソース ID。
 * 価格系検出器 (DarkPatternDetector) は独自の Severity enum を持つが、
 * 表示ラベルはテキスト系と同じ LOW/MEDIUM/HIGH 語彙を共有する。
 */
fun DarkPatternDetector.Severity.toLabelResource(): Int = when (this) {
    DarkPatternDetector.Severity.LOW -> R.string.severity_low
    DarkPatternDetector.Severity.MEDIUM -> R.string.severity_medium
    DarkPatternDetector.Severity.HIGH -> R.string.severity_high
}
