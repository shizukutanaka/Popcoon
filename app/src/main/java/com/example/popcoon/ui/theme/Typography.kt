package com.example.popcoon.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Apple iOS 17 Typography 準拠 + 日本語最適化。
 *
 * Apple HIG の重要原則:
 *  - Built-in text styles を使う (hardcoded 数値ではなく意味論的)
 *  - サイズによる自動切替 (SF Text ≤19pt / SF Display ≥20pt)
 *  - Dynamic Type 対応 (sp 単位 = システムフォントサイズに追従)
 *
 * 日本語特化の調整:
 *  - letterSpacing = 0.em (欧文向けデフォルトの追加トラッキングを無効化)
 *  - lineHeight は 1.4-1.6倍 (CJK の多バイト文字で圧迫感回避)
 *  - LineHeightStyle.Trim.None (上下対称トリム — 縦書きでも崩れない)
 *  - FontFamily.SansSerif (Noto Sans JP / Roboto Flex を端末が自動選択)
 *
 * 数値表示のための工夫:
 *  - 価格表示用 (titleLarge/headlineMedium) は FontWeight.Bold で視覚的優先度を確立
 *  - tabularNums 効果は OpenType feature で別途指定 (Compose では FontFamily に組込み)
 */
private val JpLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private val JpPlatformStyle = PlatformTextStyle(includeFontPadding = false)

private fun jpTextStyle(
    fontSize: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacingEm: Float = 0f,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = letterSpacingEm.em,
    platformStyle = JpPlatformStyle,
    lineHeightStyle = JpLineHeightStyle,
)

val PopcoonTypography = Typography(
    // ── Display: 最大級の見出し (オンボーディング / Empty State の絵文字) ──
    displayLarge = jpTextStyle(fontSize = 32, lineHeight = 44, weight = FontWeight.Bold),
    displayMedium = jpTextStyle(fontSize = 28, lineHeight = 40, weight = FontWeight.Bold),
    displaySmall = jpTextStyle(fontSize = 24, lineHeight = 36, weight = FontWeight.Bold),

    // ── Headline: 画面タイトル ──
    headlineLarge = jpTextStyle(fontSize = 22, lineHeight = 32, weight = FontWeight.SemiBold),
    headlineMedium = jpTextStyle(fontSize = 20, lineHeight = 30, weight = FontWeight.SemiBold),
    headlineSmall = jpTextStyle(fontSize = 18, lineHeight = 26, weight = FontWeight.SemiBold),

    // ── Title: セクション見出し / カード見出し ──
    titleLarge = jpTextStyle(fontSize = 18, lineHeight = 26, weight = FontWeight.SemiBold),
    titleMedium = jpTextStyle(fontSize = 16, lineHeight = 24, weight = FontWeight.Medium),
    titleSmall = jpTextStyle(fontSize = 14, lineHeight = 20, weight = FontWeight.Medium),

    // ── Body: 本文 ──
    bodyLarge = jpTextStyle(fontSize = 15, lineHeight = 22, weight = FontWeight.Normal),
    bodyMedium = jpTextStyle(fontSize = 14, lineHeight = 20, weight = FontWeight.Normal),
    bodySmall = jpTextStyle(fontSize = 12, lineHeight = 16, weight = FontWeight.Normal),

    // ── Label: ボタン / バッジ / タグ ──
    labelLarge = jpTextStyle(fontSize = 14, lineHeight = 18, weight = FontWeight.SemiBold),
    labelMedium = jpTextStyle(fontSize = 12, lineHeight = 16, weight = FontWeight.Medium),
    labelSmall = jpTextStyle(fontSize = 11, lineHeight = 14, weight = FontWeight.Medium),
)
