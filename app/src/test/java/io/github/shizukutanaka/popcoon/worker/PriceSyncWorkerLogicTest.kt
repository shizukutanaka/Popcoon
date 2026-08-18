package io.github.shizukutanaka.popcoon.worker

import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import io.github.shizukutanaka.popcoon.feature.notification.PriceAlertDebouncer
import io.github.shizukutanaka.popcoon.feature.notification.PriceAlertEvaluator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * PriceSyncWorker が使う判定ロジックの仕様テスト。
 *
 * PriceSyncWorker は実際には PriceAlertDebouncer.resolve() (1同期サイクル遅延確認つき)
 * を呼び、これが内部で PriceAlertEvaluator.evaluate() へ委譲する。以下の
 * evaluate() 直接呼び出しテスト群は、閾値・エッジトリガ等の判定ロジック自体の回帰検出
 * (PriceAlertDebouncer 越しでも判定基準は不変) を目的として残す。
 * デバウンス自体の状態遷移テストは PriceAlertDebouncerTest を参照。
 *
 * doWork() 自体は Context (通知送信・ウィジェット更新) と DataStore (UserPreferences) に
 * 直接依存するため plain JVM ユニットテストで実行できず、以前は doWork() の分岐 (通知の
 * 優先順位付け・retry 判定) が一切テストされていなかった (機能過不足監査で発見)。
 * PriceSyncPlanner にその意思決定部分だけを純粋関数として切り出したので、下の
 * PriceSyncPlanner テスト群がそれを検証する。Context に直接触れる残りの部分
 * (WorkManager 制約・通知送信・ウィジェット更新) は Instrumentation テストに委ねる。
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

    // PriceSyncWorker の実際の呼び出し経路 (PriceAlertDebouncer 越し) を確認する。
    // 初回同期で20%下落を検知しても即座には通知せず、翌日の同期で同じ値が
    // 再現して初めて発火する — 瞬間的なスクレイピングエラーによる誤通知対策。
    "実運用経路: 5000→4000 の初回同期は保留、翌日同じ値が再現して初めて PRICE_DROP" {
        val day1 = PriceAlertDebouncer.resolve(
            previousPrice = 5000L, latestPrice = 4000L, targetPrice = null,
            minDropPercent = 3, pendingPrice = null,
        )
        day1.alert.shouldNotify shouldBe false

        val day2 = PriceAlertDebouncer.resolve(
            previousPrice = day1.resolvedPrice, latestPrice = 4000L, targetPrice = null,
            minDropPercent = 3, pendingPrice = day1.newPendingPrice,
        )
        day2.alert.kind shouldBe PriceAlertEvaluator.Kind.PRICE_DROP
        day2.alert.dropPercent shouldBe 20
    }

    // ── PriceSyncPlanner (doWork() から切り出した純粋な意思決定ロジック) ──────────
    "plan: 目標到達を最優先し、次に値下がり率降順で maxNotifications 件に絞る" {
        val drops = listOf(
            testDrop("a", pct = 5, targetReached = false),
            testDrop("b", pct = 30, targetReached = false),
            testDrop("c", pct = 10, targetReached = true),
            testDrop("d", pct = 50, targetReached = false),
            testDrop("e", pct = 1, targetReached = true),
        )
        val plan = PriceSyncPlanner.plan(drops, maxNotifications = 3)
        plan.notify.map { it.item.productKey } shouldBe listOf("c", "e", "d")
    }

    // 回帰: 旧 selectNotifications は上限超過分を take() で切り落としていた。
    // PriceSyncWorker は切り落とし前に全 Drop の確定価格を DB へ書き戻すため、
    // 落とされた値下がりは基準価格が下がった後で二度と再発火せず永久に失われていた。
    // 上限超過分は必ず suppressed として返り、1 件のまとめ通知に集約される。
    "plan: 上限超過分は捨てられず suppressed に入る (情報損失なし)" {
        val drops = listOf(
            testDrop("a", pct = 5, targetReached = false),
            testDrop("b", pct = 30, targetReached = false),
            testDrop("c", pct = 10, targetReached = true),
            testDrop("d", pct = 50, targetReached = false),
            testDrop("e", pct = 1, targetReached = true),
        )
        val plan = PriceSyncPlanner.plan(drops, maxNotifications = 3)
        plan.suppressed.map { it.item.productKey } shouldBe listOf("b", "a")
        (plan.notify + plan.suppressed).size shouldBe drops.size
        (plan.notify + plan.suppressed).map { it.item.productKey }.toSet() shouldBe
            drops.map { it.item.productKey }.toSet()
    }

    // 同日に 4 件以上が目標価格に到達する状況 (楽天スーパーセール等) でも、
    // 個別通知に載らなかった目標到達が黙って消えないこと。
    "plan: 目標到達が上限を超えても超過分は suppressed で残る" {
        val drops = (1..5).map { testDrop("t$it", pct = it, targetReached = true) }
        val plan = PriceSyncPlanner.plan(drops, maxNotifications = 3)
        plan.notify.size shouldBe 3
        plan.suppressed.size shouldBe 2
        plan.suppressed.all { it.targetReached } shouldBe true
    }

    "plan: maxNotifications=0 なら全件 suppressed (個別通知ゼロでも情報は残る)" {
        val drops = listOf(testDrop("a", pct = 10, targetReached = false))
        val plan = PriceSyncPlanner.plan(drops, maxNotifications = 0)
        plan.notify shouldBe emptyList()
        plan.suppressed.map { it.item.productKey } shouldBe listOf("a")
    }

    "plan: 上限に満たなければ suppressed は空" {
        val drops = listOf(testDrop("a", pct = 10, targetReached = false))
        val plan = PriceSyncPlanner.plan(drops, maxNotifications = 3)
        plan.notify.map { it.item.productKey } shouldBe listOf("a")
        plan.suppressed shouldBe emptyList()
    }

    "plan: 入力が空でも例外なく空の計画" {
        val plan = PriceSyncPlanner.plan(emptyList(), maxNotifications = 3)
        plan.notify shouldBe emptyList()
        plan.suppressed shouldBe emptyList()
    }

    "shouldRetry: 全件失敗かつ retry 上限未満なら true" {
        PriceSyncPlanner.shouldRetry(
            failureCount = 5, totalCount = 5, runAttemptCount = 0, maxRetryAttempts = 3,
        ) shouldBe true
    }

    "shouldRetry: runAttemptCount が maxRetryAttempts に達したら false (無限リトライ防止)" {
        PriceSyncPlanner.shouldRetry(
            failureCount = 5, totalCount = 5, runAttemptCount = 3, maxRetryAttempts = 3,
        ) shouldBe false
    }

    "shouldRetry: 一部成功時は再実行しない (upsert 済みのため重複通知を避ける)" {
        PriceSyncPlanner.shouldRetry(
            failureCount = 3, totalCount = 5, runAttemptCount = 0, maxRetryAttempts = 3,
        ) shouldBe false
    }

    "shouldRetry: 全件成功時は再実行しない" {
        PriceSyncPlanner.shouldRetry(
            failureCount = 0, totalCount = 5, runAttemptCount = 0, maxRetryAttempts = 3,
        ) shouldBe false
    }

    "shouldRetry: totalCount=0 は vacuous な「全滅」判定にならず false" {
        PriceSyncPlanner.shouldRetry(
            failureCount = 0, totalCount = 0, runAttemptCount = 0, maxRetryAttempts = 3,
        ) shouldBe false
    }
})

private fun testItem(key: String) = WatchlistItem(
    productKey = key, sku = key, title = "title-$key", platform = "amazon",
    realPrice = 1000, listPrice = 1000, url = "https://example.com/$key", imageUrl = null,
)

private fun testDrop(key: String, pct: Int, targetReached: Boolean) = PriceSyncPlanner.Drop(
    item = testItem(key), latest = 1000, prev = 1200, pct = pct, targetReached = targetReached,
)
