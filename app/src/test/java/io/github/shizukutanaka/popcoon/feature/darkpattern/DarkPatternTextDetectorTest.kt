package io.github.shizukutanaka.popcoon.feature.darkpattern

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Python 参照 (proto_darkpattern_signals.py) との完全一致パリティテスト。
 * ゴールデン値は PORTING_SPEC.md #5（Python 実行で取得）。
 */
class DarkPatternTextDetectorTest : StringSpec({

    fun cats(text: String, stockCount: Int? = null) =
        DarkPatternTextDetector.detect(text, stockCount).map { it.category }.toSet()

    fun sev(text: String, category: DarkPatternTextDetector.Category, stockCount: Int? = null) =
        DarkPatternTextDetector.detect(text, stockCount).firstOrNull { it.category == category }?.severity

    // ── PORTING_SPEC.md #5 パリティ ──────────────────────────────────────────
    "パリティ: 「本日限り！残り3点。8人がカートに入れました」→ SCARCITY HIGH, SOCIAL_PROOF MEDIUM, URGENCY MEDIUM" {
        val text = "本日限り！残り3点。8人がカートに入れました"
        val result = DarkPatternTextDetector.detect(text)
        result shouldHaveSize 3
        val sc = result.first { it.category == DarkPatternTextDetector.Category.SCARCITY }
        sc.severity shouldBe DarkPatternTextDetector.Severity.HIGH
        sc.evidence shouldBe "残り3点"
        val sp = result.first { it.category == DarkPatternTextDetector.Category.SOCIAL_PROOF }
        sp.severity shouldBe DarkPatternTextDetector.Severity.MEDIUM
        val ur = result.first { it.category == DarkPatternTextDetector.Category.URGENCY }
        ur.severity shouldBe DarkPatternTextDetector.Severity.MEDIUM
        // category 昇順
        result.map { it.category } shouldBe listOf(
            DarkPatternTextDetector.Category.SCARCITY,
            DarkPatternTextDetector.Category.SOCIAL_PROOF,
            DarkPatternTextDetector.Category.URGENCY,
        )
    }

    // ── 個別ルール ─────────────────────────────────────────────────────────

    "クリーンテキストは警告なし" {
        DarkPatternTextDetector.detect("オーガニックコットン100%のタオルです。送料無料。").shouldBeEmpty()
    }

    "空白テキストは警告なし" {
        DarkPatternTextDetector.detect("  ").shouldBeEmpty()
    }

    "URGENCY: カウントダウン" {
        cats("セール残り2時間で終了") shouldContain DarkPatternTextDetector.Category.URGENCY
    }

    "URGENCY: 本日限り" {
        cats("本日限りの特別価格") shouldContain DarkPatternTextDetector.Category.URGENCY
    }

    "SCARCITY: 少数点数は HIGH" {
        sev("在庫: 残り2点", DarkPatternTextDetector.Category.SCARCITY) shouldBe
            DarkPatternTextDetector.Severity.HIGH
    }

    "SCARCITY: 大量点数は MEDIUM" {
        sev("残り50点", DarkPatternTextDetector.Category.SCARCITY) shouldBe
            DarkPatternTextDetector.Severity.MEDIUM
    }

    "SCARCITY: 在庫わずかは HIGH" {
        sev("在庫わずか！お早めに", DarkPatternTextDetector.Category.SCARCITY) shouldBe
            DarkPatternTextDetector.Severity.HIGH
    }

    "SCARCITY: stock_count<=3 は HIGH" {
        sev("通常の商品説明", DarkPatternTextDetector.Category.SCARCITY, stockCount = 1) shouldBe
            DarkPatternTextDetector.Severity.HIGH
    }

    "SCARCITY: stock_count>3 は検出しない" {
        cats("通常の商品説明", stockCount = 50) shouldNotContain DarkPatternTextDetector.Category.SCARCITY
    }

    // ── 回帰: Python 参照との乖離を実行パリティで検出 → 修正済み ──────────────
    "SCARCITY: 全角数字「残り３点」も HIGH（Python \\d は Unicode、Kotlin 既定は ASCII の乖離を修正）" {
        sev("残り３点", DarkPatternTextDetector.Category.SCARCITY) shouldBe
            DarkPatternTextDetector.Severity.HIGH
    }

    "SCARCITY: 全角数字「残り５点」は MEDIUM（parseUnicodeInt で全角→Int 変換、toInt() の例外も回避）" {
        sev("残り５点", DarkPatternTextDetector.Category.SCARCITY) shouldBe
            DarkPatternTextDetector.Severity.MEDIUM
    }

    "SCARCITY: 全角空白「残り　3　点」(U+3000) も検出（\\s の Unicode 対応）" {
        cats("残り　3　点") shouldContain DarkPatternTextDetector.Category.SCARCITY
    }

    "SCARCITY: 可視テキストが空でも stock_count<=3 なら検出（旧 isBlank 早期 return を修正）" {
        sev("", DarkPatternTextDetector.Category.SCARCITY, stockCount = 2) shouldBe
            DarkPatternTextDetector.Severity.HIGH
    }

    "SOCIAL_PROOF: 人数+閲覧" {
        cats("いま12人がこの商品を見ています") shouldContain DarkPatternTextDetector.Category.SOCIAL_PROOF
    }

    "MISDIRECTION: デフォルト選択" {
        cats("延長保証はデフォルトで選択されています") shouldContain DarkPatternTextDetector.Category.MISDIRECTION
    }

    "FORCED_ACTION: confirmshaming は HIGH" {
        val w = DarkPatternTextDetector.detect("いいえ、割引はいりません")
        w.any { it.category == DarkPatternTextDetector.Category.FORCED_ACTION &&
            it.severity == DarkPatternTextDetector.Severity.HIGH } shouldBe true
    }

    "英語パターン: SCARCITY + URGENCY + SOCIAL_PROOF" {
        val c = cats("Only 1 left, hurry! 5 people are viewing this")
        DarkPatternTextDetector.Category.SCARCITY in c shouldBe true
        DarkPatternTextDetector.Category.URGENCY in c shouldBe true
        DarkPatternTextDetector.Category.SOCIAL_PROOF in c shouldBe true
    }

    "出力は category 昇順・各カテゴリ最大1件" {
        val out = DarkPatternTextDetector.detect("本日限り 今だけ 残り1点 在庫わずか")
        val cats2 = out.map { it.category }
        cats2 shouldBe cats2.sorted()
        cats2.size shouldBe cats2.toSet().size
    }

    "決定的" {
        val text = "本日限り！残り3点。8人がカートに入れました"
        DarkPatternTextDetector.detect(text) shouldBe DarkPatternTextDetector.detect(text)
    }
})
