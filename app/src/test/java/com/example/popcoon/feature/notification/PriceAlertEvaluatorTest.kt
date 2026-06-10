package com.example.popcoon.feature.notification

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/**
 * PriceAlertEvaluator — 希望価格 / 値下がりアラート判定の純関数テスト。
 * 同種価格追跡アプリ（CamelCamelCamel 等）の目標価格通知に相当するコアロジック。
 */
class PriceAlertEvaluatorTest : StringSpec({

    val MIN_DROP = 3

    fun eval(prev: Long, latest: Long, target: Long?) =
        PriceAlertEvaluator.evaluate(prev, latest, target, MIN_DROP)

    // ── 目標価格到達 (最優先) ──────────────────────────────────────────────
    "目標価格ちょうどに一致 → TARGET_REACHED" {
        eval(prev = 5000, latest = 4000, target = 4000).kind shouldBe
            PriceAlertEvaluator.Kind.TARGET_REACHED
    }

    "目標価格を下回る → TARGET_REACHED" {
        eval(prev = 5000, latest = 3500, target = 4000).kind shouldBe
            PriceAlertEvaluator.Kind.TARGET_REACHED
    }

    "目標到達は値下がり率が小さくても通知される (率の閾値を無視)" {
        // 前回比 1% しか下がっていない (MIN_DROP=3 未満) が、目標以下なので通知
        val a = eval(prev = 4040, latest = 4000, target = 4000)
        a.kind shouldBe PriceAlertEvaluator.Kind.TARGET_REACHED
        a.dropPercent shouldBe 0 // 1% は整数切り捨てで 0
    }

    "目標到達は値上がりしていても通知される (前回より高くても目標以下なら)" {
        // 前回 3000 → 今回 3800 と値上がりしたが、目標 4000 以下なので通知
        eval(prev = 3000, latest = 3800, target = 4000).kind shouldBe
            PriceAlertEvaluator.Kind.TARGET_REACHED
    }

    "目標価格より高い → TARGET_REACHED にならない" {
        eval(prev = 5000, latest = 4001, target = 4000).kind shouldBe
            PriceAlertEvaluator.Kind.NONE
    }

    // ── 値下がり (目標未設定) ──────────────────────────────────────────────
    "目標未設定 + 有意な値下がり → PRICE_DROP" {
        val a = eval(prev = 5000, latest = 4000, target = null)
        a.kind shouldBe PriceAlertEvaluator.Kind.PRICE_DROP
        a.dropPercent shouldBe 20
    }

    "目標未設定 + 閾値ちょうどの値下がり → PRICE_DROP" {
        eval(prev = 100, latest = 97, target = null).kind shouldBe
            PriceAlertEvaluator.Kind.PRICE_DROP // 3% == MIN_DROP
    }

    "目標未設定 + 閾値未満の値下がり → NONE" {
        eval(prev = 100, latest = 98, target = null).kind shouldBe
            PriceAlertEvaluator.Kind.NONE // 2% < 3%
    }

    "目標未設定 + 値上がり → NONE" {
        eval(prev = 4000, latest = 4500, target = null).kind shouldBe
            PriceAlertEvaluator.Kind.NONE
    }

    "目標未設定 + 横ばい → NONE" {
        eval(prev = 4000, latest = 4000, target = null).kind shouldBe
            PriceAlertEvaluator.Kind.NONE
    }

    // ── 目標設定済みだが未到達 → 値下がり判定にフォールバック ────────────────
    "目標未到達でも有意な値下がりがあれば PRICE_DROP" {
        // 目標 3000 には届かないが 5000→4000 で 20% 下落
        eval(prev = 5000, latest = 4000, target = 3000).kind shouldBe
            PriceAlertEvaluator.Kind.PRICE_DROP
    }

    // ── 異常値ガード ────────────────────────────────────────────────────────
    "latest が 0 以下 → NONE (異常データ)" {
        eval(prev = 5000, latest = 0, target = 4000).kind shouldBe
            PriceAlertEvaluator.Kind.NONE
    }

    "prev が 0 (初回) + 目標到達 → TARGET_REACHED, dropPercent=0" {
        val a = eval(prev = 0, latest = 3000, target = 4000)
        a.kind shouldBe PriceAlertEvaluator.Kind.TARGET_REACHED
        a.dropPercent shouldBe 0 // 前回比は計算不能
    }

    "prev が 0 (初回) + 目標なし → NONE (値下がり率を計算できない)" {
        eval(prev = 0, latest = 3000, target = null).kind shouldBe
            PriceAlertEvaluator.Kind.NONE
    }

    "target が 0 以下 → 目標として無効、値下がり判定にフォールバック" {
        eval(prev = 5000, latest = 4000, target = 0).kind shouldBe
            PriceAlertEvaluator.Kind.PRICE_DROP
    }

    // ── minDropPercent = 0 (全ての下落を通知) ────────────────────────────────
    "minDropPercent=0 なら dropPercent>=1 の値下がりで PRICE_DROP (旧条件 minDropPercent>0 では NONE だった)" {
        // 1000→990 = 1% (整数 floor ≥ 1) → dropPercent=1 > 0 かつ >= 0 → PRICE_DROP
        val a = PriceAlertEvaluator.evaluate(
            previousPrice = 1000, latestPrice = 990, targetPrice = null, minDropPercent = 0,
        )
        a.kind shouldBe PriceAlertEvaluator.Kind.PRICE_DROP
    }

    // ── shouldNotify ────────────────────────────────────────────────────────
    "shouldNotify は NONE 以外で true" {
        eval(prev = 5000, latest = 4000, target = 4000).shouldNotify shouldBe true
        eval(prev = 5000, latest = 4900, target = null).shouldNotify shouldBe false
    }

    // ── プロパティ: 目標以下なら常に TARGET_REACHED ────────────────────────────
    "プロパティ: latest <= target (>0) なら必ず TARGET_REACHED" {
        checkAll(Arb.long(1L..1_000_000L), Arb.long(1L..1_000_000L)) { latest, target ->
            if (latest <= target) {
                eval(prev = 999_999, latest = latest, target = target).kind shouldBe
                    PriceAlertEvaluator.Kind.TARGET_REACHED
            }
        }
    }
})
