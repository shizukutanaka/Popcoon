package com.example.popcoon.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── Popcoon ブランドトークン ──────────────────────────────────────────
// Brand color: #00C4CC (ユーザー要件)
// WCAG AA  (4.5:1 以上): 全インタラクティブテキストに適用
// WCAG AAA (7.0:1 以上): 本文テキストに適用
//
// #00C4CC on White = 2.15:1 → WCAG 不合格
// #007B80 on White = 4.81:1 → WCAG AA ✅ (最も #00C4CC に近い AA 準拠色)
// #005054 on White = 9.23:1 → WCAG AAA ✅
// #0A1519 on White = 18.5:1 → WCAG AAA ✅
//
// 戦略:
//  - #00C4CC は装飾・Container 背景・アイコン色として使用 (テキスト不可)
//  - primary (テキスト/ボタン) → #007B80 (AA 準拠)
//  - secondary (ダーク variant) → #005054 (AAA 準拠)
private val Pop_Teal         = Color(0xFF00C4CC)   // ブランド色 (装飾専用)
private val Pop_TealAA       = Color(0xFF007B80)   // #00C4CC 系 AA 準拠 (4.81:1)
private val Pop_TealAAA      = Color(0xFF005054)   // #00C4CC 系 AAA 準拠 (9.23:1)
private val Pop_TealDark     = Color(0xFF00363A)   // ダーク背景上の primaryContainer (onPrimaryContainer = Pop_TealLight が可読)
private val Pop_TealLight    = Color(0xFFB3ECEF)   // Container 背景
private val Pop_TealVivid    = Color(0xFF00C4CC)   // Chip/Badge 背景 (on White ≥ 4.5 不要)
private val Pop_Ink          = Color(0xFF0A1519)   // 本文 (18.5:1 AAA)
private val Pop_InkSubtle    = Color(0xFF2A3D44)   // 副次テキスト (12:1)
private val Pop_Canvas       = Color(0xFFFAFBFB)
private val Pop_Border       = Color(0xFFE3E8EA)

private val Status_Good      = Color(0xFF118A4E)   // BUY_NOW (4.5:1 on White AA)
private val Status_Warning   = Color(0xFF8B6800)   // 注意 (修正: B8860B = 3.25:1 → 8B6800 = 5.2:1)
private val Status_Bad       = Color(0xFFC0392B)   // WAIT (5.44:1 AA)
private val Status_Info      = Color(0xFF1B6AB3)

private val LightColors = lightColorScheme(
    primary = Pop_TealAA,          // テキスト/ボタン: #007B80 (AA 4.81:1)
    onPrimary = Color.White,
    primaryContainer = Pop_TealLight,
    onPrimaryContainer = Pop_Ink,
    secondary = Pop_TealAAA,       // セカンダリ: #005054 (AAA 9.23:1)
    onSecondary = Color.White,
    background = Pop_Canvas,
    onBackground = Pop_Ink,
    surface = Color.White,
    onSurface = Pop_Ink,
    surfaceVariant = Color(0xFFF2F5F6),
    onSurfaceVariant = Pop_InkSubtle,
    outline = Pop_Border,
    error = Status_Bad,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Pop_Teal,
    onPrimary = Pop_Ink,
    primaryContainer = Pop_TealDark,
    onPrimaryContainer = Pop_TealLight,
    secondary = Pop_TealLight,
    onSecondary = Pop_Ink,
    background = Color(0xFF0A1519),
    onBackground = Color(0xFFE3E8EA),
    surface = Color(0xFF13232A),
    onSurface = Color(0xFFE3E8EA),
    error = Status_Bad,
    onError = Color.White,
)

@Composable
fun PopcoonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,  // デフォOFF: ブランド一貫性優先
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = PopcoonTypography,
        content = content,
    )
}
