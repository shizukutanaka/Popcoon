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

    // ── 在庫助数詞の recall 拡張 (点以外: 個/セット/台、接頭辞 あと) ────────────────
    "SCARCITY: 「残り3個」も点と同義で HIGH" {
        sev("残り3個", DarkPatternTextDetector.Category.SCARCITY) shouldBe
            DarkPatternTextDetector.Severity.HIGH
    }

    "SCARCITY: 「あと2セット」(接頭辞あと + 助数詞セット) も HIGH" {
        sev("あと2セット", DarkPatternTextDetector.Category.SCARCITY) shouldBe
            DarkPatternTextDetector.Severity.HIGH
    }

    "SCARCITY: 「残り20個」は MEDIUM (4以上)" {
        sev("残り20個", DarkPatternTextDetector.Category.SCARCITY) shouldBe
            DarkPatternTextDetector.Severity.MEDIUM
    }

    "SCARCITY: 「あと5日で発送」の 日 は在庫助数詞でないので検出しない" {
        cats("あと5日で発送") shouldNotContain DarkPatternTextDetector.Category.SCARCITY
    }

    "SCARCITY: 「残り3時間」は URGENCY であって SCARCITY ではない" {
        cats("残り3時間") shouldNotContain DarkPatternTextDetector.Category.SCARCITY
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

    "HIDDEN_SUBSCRIPTION: 隠れ定期購入は HIGH" {
        val w = DarkPatternTextDetector.detect("定期購入コースへ自動で切替")
        w.any { it.category == DarkPatternTextDetector.Category.HIDDEN_SUBSCRIPTION &&
            it.severity == DarkPatternTextDetector.Severity.HIGH } shouldBe true
    }

    "HIDDEN_SUBSCRIPTION: 自動更新・英語 auto-renew も検出" {
        cats("ご注文は自動更新されます") shouldContain
            DarkPatternTextDetector.Category.HIDDEN_SUBSCRIPTION
        cats("This subscription automatically renews monthly") shouldContain
            DarkPatternTextDetector.Category.HIDDEN_SUBSCRIPTION
    }

    "HIDDEN_SUBSCRIPTION: 中立な単発商品は誤検出しない" {
        cats("高品質なワイヤレスイヤホン 送料無料") shouldNotContain
            DarkPatternTextDetector.Category.HIDDEN_SUBSCRIPTION
    }

    // ── OBSTRUCTION (解約妨害) ───────────────────────────────────────────────
    "OBSTRUCTION: 解約手段を電話に限定する表現は HIGH" {
        listOf(
            "解約はお電話のみで承ります",
            "解約のご連絡はお電話に限ります",
            "退会は電話だけの受付です",
            "定期の停止はお電話のみ",
            "お電話のみでの解約受付となります",
            "Cancellation is accepted by phone only",
            "Call us to cancel your subscription",
        ).forEach { text ->
            sev(text, DarkPatternTextDetector.Category.OBSTRUCTION) shouldBe
                DarkPatternTextDetector.Severity.HIGH
        }
    }

    "OBSTRUCTION: 次回発送日起点の事前連絡期限は MEDIUM" {
        listOf(
            "解約は次回お届け予定日の10日前までにご連絡ください",
            "次回発送日の5日前までにお申し出ください",
            "次回の配送の7日前までに解約手続きが必要です",
            "You must cancel at least 10 days before the next shipment",
        ).forEach { text ->
            sev(text, DarkPatternTextDetector.Category.OBSTRUCTION) shouldBe
                DarkPatternTextDetector.Severity.MEDIUM
        }
    }

    "OBSTRUCTION: 電話限定 (HIGH) が期限 (MEDIUM) より優先" {
        sev(
            "解約は次回発送の10日前までにご連絡ください。お手続きは解約専用のお電話のみ",
            DarkPatternTextDetector.Category.OBSTRUCTION,
        ) shouldBe DarkPatternTextDetector.Severity.HIGH
    }

    "OBSTRUCTION: 複数手段の提示・無関係な電話案内/期限は誤検出しない" {
        listOf(
            "解約は電話またはマイページからいつでも可能です",
            "解約はマイページからいつでも手続きできます",
            "解約はマイページから可能ですがお問い合わせはお電話のみ",
            "お問い合わせはお電話のみ受け付けています",
            "次回お届け日の変更は3日前まで可能です",
            "高品質なワイヤレスイヤホン 送料無料",
        ).forEach { text ->
            cats(text) shouldNotContain DarkPatternTextDetector.Category.OBSTRUCTION
        }
    }

    "OBSTRUCTION: MISDIRECTION と SCARCITY の間に並ぶ (アルファベット順)" {
        val out = DarkPatternTextDetector.detect("本日限り 残り2点 デフォルトでチェック 解約はお電話のみ")
        out.map { it.category } shouldBe listOf(
            DarkPatternTextDetector.Category.MISDIRECTION,
            DarkPatternTextDetector.Category.OBSTRUCTION,
            DarkPatternTextDetector.Category.SCARCITY,
            DarkPatternTextDetector.Category.URGENCY,
        )
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
