package io.github.shizukutanaka.popcoon.worker

import io.github.shizukutanaka.popcoon.feature.notification.PriceAlertEvaluator
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

    // エッジトリガ: 目標を「上→下」に跨いだ同期のみ TARGET_REACHED。
    // previousPrice > targetPrice でなければ「跨ぎ」と見なされず TARGET にならない。
    "目標を上→下に跨いだ場合、dropPercent が minDropPercent 未満でも TARGET_REACHED" {
        val alert = PriceAlertEvaluator.evaluate(
            previousPrice = 5001L,  // 目標より 1 円高い (目標超)
            latestPrice = 4900L,    // 目標以下 (2% 下落だが 10% min 未満)
            targetPrice = 5000L,
            minDropPercent = 10,
        )
        alert.kind shouldBe PriceAlertEvaluator.Kind.TARGET_REACHED
        alert.shouldNotify shouldBe true
    }

    "previousPrice が targetPrice に等しい場合は跨ぎでない → エッジ発火しない" {
        // prev=5000, target=5000 → wasAlreadyAtOrBelowTarget=true (5000 in 1..5000) → NONE
        val alert = PriceAlertEvaluator.evaluate(
            previousPrice = 5000L,
            latestPrice = 4900L,
            targetPrice = 5000L,
            minDropPercent = 10,
        )
        alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
        alert.shouldNotify shouldBe false
    }

    // 識別: WorkManager は WORK_NAME で enqueue/cancel を紐付ける。値が変わると
    // 旧スケジュールがキャンセルされず二重同期になるため、具体値を固定する。
    "WORK_NAME は 'price_sync_daily' (WorkManager スケジュール一意識別子)" {
        PriceSyncWorker.WORK_NAME shouldBe "price_sync_daily"
    }
})
