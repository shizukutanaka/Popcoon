import com.example.popcoon.core.CurrencyFormatter
package com.example.popcoon.ui.a11y

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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

/**
 * Verdict バッジ用のアクセシブルラベル生成。
 *
 * 「BUY_NOW」だけでは TalkBack で意味不明 →
 * 「買い時、スコア85点」のような完全な文を読み上げる。
 */
fun verdictA11yLabel(verdictName: String, score: Int? = null): String {
    val verdict = when (verdictName) {
        "BUY_NOW" -> "買い時"
        "NEUTRAL" -> "様子見"
        "WAIT" -> "待ち推奨"
        else -> verdictName
    }
    return if (score != null) "$verdict、買い時スコア${score}点" else verdict
}

/** 価格を読み上げ用に整形 (¥1,234 → 「1,234円」) */
fun priceA11yLabel(yenAmount: Long): String =
    CurrencyFormatter.yenAccessible(yenAmount)

/** ダークパターン警告を文章化 */
fun darkPatternA11yLabel(typeName: String): String = when (typeName) {
    "ALWAYS_ON_DISCOUNT" -> "常設セールの警告"
    "INFLATED_LIST_PRICE" -> "参考価格誇張の警告"
    "PRE_SALE_MARKUP" -> "セール前値上げの警告"
    "CHARM_PRICING" -> "端数価格の警告"
    else -> "$typeName の警告"
}
