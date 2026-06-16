package com.example.popcoon.ui.a11y

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.popcoon.core.CurrencyFormatter
import com.example.popcoon.ui.theme.TouchTarget

/**
 * アクセシビリティ拡張。
 *
 * Material Design + WCAG ガイドライン準拠:
 *  - タッチターゲット最小 48dp × 48dp (Android Accessibility Suite 標準)
 *  - スクリーンリーダー (TalkBack) 用 contentDescription 必須
 *  - heading 階層を semantics で明示
 *
 * 参考: WCAG 2.1 AAA + Material Design Touch Targets
 */

/** 最小タッチサイズ 48dp 確保 (アクセシビリティ違反を防止) */
fun Modifier.a11yMinTouchTarget(): Modifier =
    this.defaultMinSize(minWidth = TouchTarget.min, minHeight = TouchTarget.min)

/** TalkBack で「見出し」と認識させる */
fun Modifier.a11yHeading(): Modifier = this.semantics { heading() }

/** TalkBack 読み上げ用の説明を追加 (装飾要素は contentDescription = null) */
fun Modifier.a11yDescription(description: String): Modifier =
    this.semantics { contentDescription = description }

/** 価格を読み上げ用に整形 (¥1,234 → 「1,234円」) */
fun priceA11yLabel(yenAmount: Long): String =
    CurrencyFormatter.yenAccessible(yenAmount)

/**
 * ダークパターン警告を文章化。
 *
 * 価格系（DarkPatternDetector）と UI テキスト系（DarkPatternTextDetector, PORTING_SPEC.md #5）
 * 両方のカテゴリ名を TalkBack 向けに日本語化する。
 */
fun darkPatternA11yLabel(typeName: String): String = when (typeName) {
    // 価格系 (DarkPatternDetector.WarningType)
    "ALWAYS_ON_DISCOUNT"  -> "常設セールの警告"
    "INFLATED_LIST_PRICE" -> "参考価格誇張の警告"
    "PRE_SALE_MARKUP"     -> "セール前値上げの警告"
    "CHARM_PRICING"       -> "端数価格の警告"
    "FAKE_SCARCITY"       -> "偽希少性の警告"
    "COUNTDOWN_MANIPULATION" -> "カウントダウン操作の警告"
    "DRIP_PRICING"        -> "隠れたコストの警告"
    // UIテキスト系 (DarkPatternTextDetector.Category)
    "URGENCY"             -> "緊急性を煽る表現の警告"
    "SCARCITY"            -> "在庫を煽る表現の警告"
    "SOCIAL_PROOF"        -> "社会的証明操作の警告"
    "MISDIRECTION"        -> "誤誘導の警告"
    "FORCED_ACTION"       -> "意思決定の強制（confirmshaming）の警告"
    else -> "${typeName}の警告"
}
