package io.github.shizukutanaka.popcoon.ui.theme

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqualTo
import kotlin.math.pow

/**
 * WCAG 2.1 コントラスト比テスト。
 *
 * 発見と修正:
 *  - #00C4CC (ブランドカラー) on White = 2.15:1 — WCAG 不合格
 *  - 修正: primary = #007B80 (5.07:1 AA), secondary = #005054 (9.23:1 AAA)
 *  - #00C4CC は装飾・Container 背景専用に変更
 */
class WcagContrastTest : StringSpec({

    fun linearize(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(r: Int, g: Int, b: Int): Double =
        0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    fun contrast(l1: Double, l2: Double): Double {
        val lighter = maxOf(l1, l2); val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    val white = luminance(255, 255, 255)
    val black = luminance(0, 0, 0)
    val primaryAA  = luminance(0, 123, 128)   // #007B80 — primary  (5.07:1 AA)
    val primaryAAA = luminance(0, 80, 84)     // #005054 — secondary (9.23:1 AAA)
    val ink        = luminance(10, 21, 25)    // #0A1519 — 本文 (18.5:1 AAA)
    val success    = luminance(13, 120, 64)   // #0D7840 — BUY_NOW  (5.56:1 AA)
    val error      = luminance(192, 57, 43)   // #C0392B — WAIT     (5.44:1 AA)
    val warning    = luminance(139, 104, 0)   // #8B6800 — 注意     (5.15:1 AA)

    "Primary (#007B80) on White — WCAG AA (>=4.5:1)" {
        contrast(primaryAA, white) shouldBeGreaterThanOrEqualTo 4.5
    }
    "White on Primary (#007B80) — WCAG AA (>=4.5:1)" {
        contrast(white, primaryAA) shouldBeGreaterThanOrEqualTo 4.5
    }
    "Secondary (#005054) on White — WCAG AAA (>=7.0:1)" {
        contrast(primaryAAA, white) shouldBeGreaterThanOrEqualTo 7.0
    }
    "Ink (#0A1519) on White — WCAG AAA (>=7.0:1)" {
        contrast(ink, white) shouldBeGreaterThanOrEqualTo 7.0
    }
    "Success (#0D7840) on White — WCAG AA (>=4.5:1)" {
        contrast(success, white) shouldBeGreaterThanOrEqualTo 4.5
    }
    "Error (#C0392B) on White — WCAG AA (>=4.5:1)" {
        contrast(error, white) shouldBeGreaterThanOrEqualTo 4.5
    }
    "Warning (#8B6800) on White — WCAG AA (>=4.5:1)" {
        contrast(warning, white) shouldBeGreaterThanOrEqualTo 4.5
    }
    "Black on White — 最大コントラスト (>=21.0:1)" {
        contrast(black, white) shouldBeGreaterThanOrEqualTo 21.0
    }
})
