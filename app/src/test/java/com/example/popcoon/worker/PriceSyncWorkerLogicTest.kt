package com.example.popcoon.worker

import com.example.popcoon.feature.notification.PriceAlertEvaluator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * PriceSyncWorker が使う PriceAlertEvaluator の仕様テスト。
 *
 * 本番コード (PriceAlertEvaluator.evaluate) を直接呼ぶことで、
 * 閾値やロジック変更時の回帰を確実に検出する。
 * Context 依存部分 (WorkManager 制約・バックオフ) は Instrumentation テストに委ねる。
 */
class PriceSyncWorkerLogicTest : StringSpec({

    "5000→4000 (20%) は MIN_DROP=3% を超えるため PRICE_DROP" {
        val alert = PriceAlertEvaluator.evaluate(
            previousPrice = 5000L,
            latestPrice = 4000L,
            targetPrice = null,
            minDropPercent = 3,
        )
        alert.kind shouldBe PriceAlertEvaluator.Kind.PRICE_DROP
        alert.dropPercent shouldBe 20
        alert.shouldNotify shouldBe true
    }

    "1000→999 (0%, 整数 floor) は MIN_DROP=3% 未満 → NONE" {
        val alert = PriceAlertEvaluator.evaluate(
            previousPrice = 1000L,
            latestPrice = 999L,
            targetPrice = null,
            minDropPercent = 3,
        )
        alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
        alert.shouldNotify shouldBe false
    }

    "値上がり → NONE (通知しない)" {
        val alert = PriceAlertEvaluator.evaluate(
            previousPrice = 4000L,
            latestPrice = 5000L,
            targetPrice = null,
            minDropPercent = 3,
        )
        alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
        alert.shouldNotify shouldBe false
    }

    "同価格 → NONE" {
        val alert = PriceAlertEvaluator.evaluate(
            previousPrice = 3000L,
            latestPrice = 3000L,
            targetPrice = null,
            minDropPercent = 3,
        )
        alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
        alert.shouldNotify shouldBe false
    }

    "targetPrice 到達は dropPercent 未満でも TARGET_REACHED (優先)" {
        val alert = PriceAlertEvaluator.evaluate(
            previousPrice = 5000L,
            latestPrice = 4900L,
            targetPrice = 5000L,  // 目標を下回った
            minDropPercent = 10,  // 2% 値下がりだが 10% 未満
        )
        alert.kind shouldBe PriceAlertEvaluator.Kind.TARGET_REACHED
        alert.shouldNotify shouldBe true
    }

    "WORK_NAME は一意識別子 (非空)" {
        PriceSyncWorker.WORK_NAME.isNotEmpty() shouldBe true
    }
})
