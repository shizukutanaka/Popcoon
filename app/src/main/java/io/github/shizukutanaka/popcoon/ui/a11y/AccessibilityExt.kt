package io.github.shizukutanaka.popcoon.ui.a11y

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.ui.theme.TouchTarget

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
 * 純粋に装飾的な要素 (絵文字イラスト等) を TalkBack から完全に隠す。
 *
 * 例えばオンボーディングのヒーロー絵文字や空状態のイラストは、Text として
 * 描画すると TalkBack が Unicode 絵文字名 (「的が付いた矢」等) をそのまま読み上げてしまい、
 * 直後に続く本来の見出しテキストの前にノイズが入る (商用リリース監査で発見)。
 * 隣接する見出し/本文テキストが同じ情報を既に伝えている場合にのみ使うこと —
 * その絵文字が唯一の情報源である場合は a11yDescription() で明示的な説明を与えるべき。
 */
fun Modifier.a11yDecorative(): Modifier = this.clearAndSetSemantics {}

/**
 * 価格を読み上げ用にロケール対応で整形する (例: JA「1,234円」、EN "1,234 yen")。
 *
 * 以前は CurrencyFormatter.yenAccessible() の「1,234円」をそのまま全ロケールの
 * TalkBack 読み上げに使っており、検索結果一覧という最高トラフィック画面で
 * EN/KO/ZH ユーザーにも日本語漢字「円」が読み上げられていた (商用リリース監査で発見)。
 * CurrencyFormatter.yenAccessible() 自体は CurrencyFormatterTest.kt / kotlin_parity が
 * 固定フォーマット ("1,234円") を前提にテストしているため変更できず、
 * ここでロケール対応の読み上げ文言を別途組み立てる。
 */
@Composable
fun priceA11yLabel(yenAmount: Long): String =
    stringResource(R.string.price_a11y_yen, String.format(java.util.Locale.US, "%,d", yenAmount))

/**
 * ダークパターン警告を文章化。
 *
 * 価格系（DarkPatternDetector）と UI テキスト系（DarkPatternTextDetector, PORTING_SPEC.md #5）
 * 両方のカテゴリ名を TalkBack 向けに日本語化する。
 *
 * 現状どこからも呼ばれていない (grep で確認済み) — 実際の警告チップは
 * `.a11yDescription("⚠ ${text}")` で可視テキストをそのまま再利用しており
 * (ui/screens/search/ProductRow.kt の SaleChip 等)、こちらは ja 固定の別経路として
 * 残っている。i18n を今直すより、まず削除するか実際に配線するかの製品判断が先に必要
 * (商用リリース品質監査で発見 — 過去に同種の verdictA11yLabel は削除・置換済み、
 * このコメント直上参照)。安全側に倒し、テスト済みの既存関数を無断で削除しない。
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
