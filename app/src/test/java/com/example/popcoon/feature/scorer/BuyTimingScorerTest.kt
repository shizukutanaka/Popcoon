package com.example.popcoon.feature.scorer

import com.example.popcoon.data.model.PriceRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Instant

/**
 * BuyTimingScorer テスト。
 *
 * 競合 14 アプリ全社が非搭載の独自機能。
 * Popcoon の差別化の核心なので最高密度でテストする。
 *
 * Python TDD 参照層 (BuyTimingScorer) との整合も確認:
 *  - 閾値: BUY_NOW >= 70 / NEUTRAL 36-69 / WAIT <= 35
 *  - MIN_HISTORY = 14
 *  - BASE_SCORE = 50
 */
class BuyTimingScorerTest : StringSpec({

    fun priceRecord(price: Long, daysAgo: Long = 0): PriceRecord = PriceRecord(
        productKey = "test:P1",
        platform = "amazon",
        listPrice = price + 500,
        realPrice = price,
        recordedAt = Instant.now().minusSeconds(daysAgo * 86400),
    )

    fun stableHistory(price: Long, days: Int = 30): List<PriceRecord> =
        (days downTo 0).map { d -> priceRecord(price, d.toLong()) }

    // ── 基本 ────────────────────────────────────────────────────────────────
    "履歴が 14 件未満なら null" {
        BuyTimingScorer.score(1000, 1500, List(13) { priceRecord(1000, it.toLong()) }) shouldBe null
    }

    "履歴が 14 件以上なら Score を返す" {
        BuyTimingScorer.score(1000, 1500, List(14) { priceRecord(1000, it.toLong()) }) shouldNotBe null
    }

    "total は 0-100 の範囲内" {
        val h = stableHistory(1000, 30)
        val s = BuyTimingScorer.score(1000, 1500, h)!!
        s.total shouldBeGreaterThan -1
        s.total shouldBeLessThan 101
    }

    // ── Verdict 閾値 ────────────────────────────────────────────────────────
    "大幅割引+低変動で BUY_NOW になる" {
        // 29日間 2000円の安定価格から 1000円に下落: 60%OFF (+15) + 極安定 (+10) → 75以上 → BUY_NOW
        // Note: high==low なので ATL シグナルは 0 (安定判定不能)。discount と volatility が主ドライバー。
        val h = (29 downTo 1).map { d -> priceRecord(2000, d.toLong()) }
        val s = BuyTimingScorer.score(1000, 2500, h)
        s?.verdict shouldBe BuyTimingScorer.Verdict.BUY_NOW
    }

    "安い時期に比べて割高な状態では BUY_NOW にならない" {
        // 29日間 1000円安定 + 今日 2000円: 割引なし/小、high==low なので ATL=0
        // → total ≈ 50+5+10 = 65 → NEUTRAL (BUY_NOW にはならない)
        val h = (29 downTo 1).map { d -> priceRecord(1000, d.toLong()) }
        val s = BuyTimingScorer.score(2000, 2500, h)
        s.shouldNotBeNull()
        s.verdict shouldNotBe BuyTimingScorer.Verdict.BUY_NOW
    }

    "安定価格なら NEUTRAL" {
        val h = stableHistory(1000, 30)
        val s = BuyTimingScorer.score(1000, 1500, h)!!
        // 変動なし → trend 0 / ATL 中間 / volatility 0 → 中立付近
        s.verdict shouldBe BuyTimingScorer.Verdict.NEUTRAL
    }

    // ── Confidence ──────────────────────────────────────────────────────────
    "14-29日履歴 → LOW confidence" {
        val h = stableHistory(1000, 14)
        BuyTimingScorer.score(1000, 1500, h)!!.confidence shouldBe "LOW"
    }

    "30-89日履歴 → MEDIUM confidence" {
        val h = stableHistory(1000, 30)
        BuyTimingScorer.score(1000, 1500, h)!!.confidence shouldBe "MEDIUM"
    }

    "90日以上履歴 → HIGH confidence" {
        val h = stableHistory(1000, 90)
        BuyTimingScorer.score(1000, 1500, h)!!.confidence shouldBe "HIGH"
    }

    // ── Signals ─────────────────────────────────────────────────────────────
    // Python oracle (popcoon-tdd/buy_timing_scorer.py) で検証:
    //   stableHistory(1000, 30): 31件全て realPrice=1000, listPrice=1500
    //   → belowRate = 1.0 ≥ 0.9 → ALWAYS_ON_DISCOUNT 発火 → -8ペナルティ
    //   → total = 50(中立) + 10(定価比33%OFF) + 10(極めて安定) + 5(十分な履歴) - 8(ダークパターン) = 67
    //   旧コメントは「volatility 0」と書いていたが、cv=0 < 0.02 なので「極めて安定 +10」が正しい。
    "signals の内容が Python オラクルと一致する (識別テスト)" {
        val h = stableHistory(1000, 30)
        val s = BuyTimingScorer.score(1000, 1500, h)!!
        // 具体値を固定: ロジックの変更で即座に検出できる
        s.total shouldBe 67
        // signals の和 == total (クリップが無い場合)
        s.signals.sumOf { it.contribution } shouldBe 67
        // 主要シグナルの存在を識別
        s.signals.any { it.name == "中立スコア" && it.contribution == 50 } shouldBe true
        s.signals.any { it.name.contains("33%OFF") && it.contribution == 10 } shouldBe true
        s.signals.any { it.name == "極めて安定" && it.contribution == 10 } shouldBe true
        s.signals.any { it.name.contains("ダークパターン") && it.contribution == -8 } shouldBe true
    }

    // ── listPrice == 0 の安全性 ──────────────────────────────────────────────
    "listPrice = 0 でも例外なし" {
        val h = stableHistory(1000, 30)
        BuyTimingScorer.score(1000, 0L, h)  // 例外なく完了すれば OK
    }

    // ── Property test ────────────────────────────────────────────────────────
    "任意の入力で total が 0-100 の範囲内" {
        checkAll(Arb.long(100L..100_000L), Arb.long(0L..200_000L)) { current, list ->
            val h = stableHistory(current, 30)
            BuyTimingScorer.score(current, list, h)?.let { s ->
                (s.total in 0..100) shouldBe true
            }
        }
    }

    // ── セール接近シグナル (arXiv 2405.13995 季節性考慮) ──────────────────────
    "today 未指定なら従来通り (後方互換)" {
        val h = stableHistory(1000, 30)
        val without = BuyTimingScorer.score(1000, 1500, h)
        val withNull = BuyTimingScorer.score(1000, 1500, h, today = null)
        without?.total shouldBe withNull?.total
    }

    // 回帰防止: 旧テストは 7/8 (プライムデーまで8日) を使っていたが、
    // signalUpcomingSale は daysUntil ∈ 4..7 しかシグナルを返さない (8日はシグナル=0)。
    // base (today=null) と nearSale (7/8) の rawSum は同じになり、<= は x<=x で常に真。
    // 識別テスト: 7/12 = プライムデー (7/16) 4 日前 (daysUntil=4 → 「大型セール接近」−6)。
    // 一定価格履歴で DOW シグナル = 0、唯一の差分は sale proximity シグナルのみ。
    // "大型セール接近 (...)" という名前のシグナルが −6 で存在することを直接検証する。
    "大型セール 4日前は -6 シグナルが発火する (識別: 具体シグナル名・値を固定)" {
        val h = stableHistory(1000, 30)
        val nearSale = BuyTimingScorer.score(
            1000, 1500, h,
            today = java.time.LocalDate.of(2026, 7, 12), // プライムデー (7/16) 4日前
        )!!
        val saleSig = nearSale.signals.find { it.name.contains("大型セール接近") }
        saleSig shouldNotBe null
        saleSig!!.contribution shouldBe -6
    }

    "セール接近シグナルでも total は 0-100 範囲内" {
        val h = stableHistory(1000, 30)
        checkAll(Arb.long(100L..100_000L)) { current ->
            BuyTimingScorer.score(
                current, current * 2, stableHistory(current, 30),
                today = java.time.LocalDate.of(2026, 11, 25),
            )?.let { (it.total in 0..100) shouldBe true }
        }
    }
})
