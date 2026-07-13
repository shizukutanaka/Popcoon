package io.github.shizukutanaka.popcoon.feature.notification

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * PriceAlertDebouncer — 「1同期サイクル遅延確認」のテスト。
 * PriceAlertEvaluator 自体の判定ロジック (エッジトリガ等) は PriceAlertEvaluatorTest で
 * 網羅済みのため、ここでは保留/確認の状態遷移だけを検証する。
 */
class PriceAlertDebouncerTest : StringSpec({

    "初回の値下がり観測は即座に発火せず保留する" {
        val r = PriceAlertDebouncer.resolve(
            previousPrice = 5000, latestPrice = 4000, targetPrice = null,
            minDropPercent = 3, pendingPrice = null,
        )
        r.alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
        r.resolvedPrice shouldBe 5000L   // 基準はまだ動かさない
        r.newPendingPrice shouldBe 4000L // 観測値を保留
    }

    "保留値と同じ価格が次回同期で再現すると確認され発火する" {
        val r = PriceAlertDebouncer.resolve(
            previousPrice = 5000, latestPrice = 4000, targetPrice = null,
            minDropPercent = 3, pendingPrice = 4000,
        )
        r.alert.kind shouldBe PriceAlertEvaluator.Kind.PRICE_DROP
        r.alert.dropPercent shouldBe 20  // 保留開始前の basis (5000) で計算
        r.resolvedPrice shouldBe 4000L
        r.newPendingPrice shouldBe null
    }

    "目標価格到達も同じ経路でデバウンスされる" {
        val first = PriceAlertDebouncer.resolve(
            previousPrice = 5000, latestPrice = 3800, targetPrice = 4000,
            minDropPercent = 10, pendingPrice = null,
        )
        first.alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
        first.newPendingPrice shouldBe 3800L

        val confirmed = PriceAlertDebouncer.resolve(
            previousPrice = 5000, latestPrice = 3800, targetPrice = 4000,
            minDropPercent = 10, pendingPrice = 3800,
        )
        confirmed.alert.kind shouldBe PriceAlertEvaluator.Kind.TARGET_REACHED
    }

    "再現しなかった観測 (瞬間的な誤値) は発火せず新しい値で保留し直す" {
        // 前回保留値 4000 だったが今回は 3000 (別の値) → 一致しないため誤値とみなし発火しない。
        // 新しい観測 (3000) 自体が通知に値するなら、それを新たに保留する。
        val r = PriceAlertDebouncer.resolve(
            previousPrice = 5000, latestPrice = 3000, targetPrice = null,
            minDropPercent = 3, pendingPrice = 4000,
        )
        r.alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
        r.resolvedPrice shouldBe 5000L   // 基準は動かさない (まだ何も確認されていない)
        r.newPendingPrice shouldBe 3000L // 新しい観測値で保留し直す
    }

    "通知に値しない変化 (値上がり) は保留せず即座に基準を更新する" {
        val r = PriceAlertDebouncer.resolve(
            previousPrice = 4000, latestPrice = 4500, targetPrice = null,
            minDropPercent = 3, pendingPrice = null,
        )
        r.alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
        r.resolvedPrice shouldBe 4500L
        r.newPendingPrice shouldBe null
    }

    "通知に値しない僅少下落は保留せず即座に基準を更新する" {
        val r = PriceAlertDebouncer.resolve(
            previousPrice = 1000, latestPrice = 990, targetPrice = null,
            minDropPercent = 3, pendingPrice = null,
        )
        r.alert.kind shouldBe PriceAlertEvaluator.Kind.NONE  // 1% < 3%
        r.resolvedPrice shouldBe 990L
        r.newPendingPrice shouldBe null
    }

    "異常値 (0以下) は previousPrice/pendingPrice をどちらも一切変更しない" {
        val r = PriceAlertDebouncer.resolve(
            previousPrice = 5000, latestPrice = 0, targetPrice = null,
            minDropPercent = 3, pendingPrice = 4000,
        )
        r.alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
        r.resolvedPrice shouldBe 5000L
        r.newPendingPrice shouldBe 4000L  // 保留中の値も維持 (異常値で確認待ちを解除しない)
    }

    "保留なし・通知不要な観測が続く限り毎回即座に基準を更新し続ける" {
        var price = 5000L
        val observations = listOf(5010L, 4990L, 5005L)  // すべて閾値未満の小動き
        var pending: Long? = null
        for (obs in observations) {
            val r = PriceAlertDebouncer.resolve(
                previousPrice = price, latestPrice = obs, targetPrice = null,
                minDropPercent = 5, pendingPrice = pending,
            )
            r.alert.kind shouldBe PriceAlertEvaluator.Kind.NONE
            price = r.resolvedPrice
            pending = r.newPendingPrice
        }
        price shouldBe 5005L
        pending shouldBe null
    }
})
