package io.github.shizukutanaka.popcoon.ui.a11y

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class AccessibilityExtTest : StringSpec({

    // verdictA11yLabel は廃止: VerdictBadge が可視ラベル (R.string.verdict_*) を再利用し
    // a11y_verdict_score テンプレートで TalkBack 文を組むためロケール対応になった。

    // ── darkPatternA11yLabel: 価格系カテゴリ ──────────────────────────────────
    "ALWAYS_ON_DISCOUNT は日本語ラベルを返す" {
        darkPatternA11yLabel("ALWAYS_ON_DISCOUNT") shouldContain "常設セール"
    }

    "DRIP_PRICING は日本語ラベルを返す" {
        darkPatternA11yLabel("DRIP_PRICING") shouldContain "隠れたコスト"
    }

    // ── darkPatternA11yLabel: テキスト系 5 カテゴリ（PORTING_SPEC.md #5）────────
    "URGENCY は日本語ラベルを返す" {
        darkPatternA11yLabel("URGENCY") shouldContain "緊急性"
    }

    "SCARCITY は日本語ラベルを返す" {
        darkPatternA11yLabel("SCARCITY") shouldContain "在庫"
    }

    "SOCIAL_PROOF は日本語ラベルを返す" {
        darkPatternA11yLabel("SOCIAL_PROOF") shouldContain "社会的証明"
    }

    "MISDIRECTION は日本語ラベルを返す" {
        darkPatternA11yLabel("MISDIRECTION") shouldContain "誤誘導"
    }

    "FORCED_ACTION は日本語ラベルを返す" {
        darkPatternA11yLabel("FORCED_ACTION") shouldContain "confirmshaming"
    }

    "未知のカテゴリはフォールバックラベルを返す（英名を含む）" {
        val label = darkPatternA11yLabel("TOTALLY_UNKNOWN")
        label shouldContain "TOTALLY_UNKNOWN"
        label shouldNotContain "null"
    }
})
