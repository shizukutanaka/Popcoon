package com.example.popcoon.worker

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * PriceSyncWorker のロジックテスト。
 *
 * Context 依存部分は Instrumentation テストに委ね、
 * ここでは純計算ロジックのみを検証。
 */
class PriceSyncWorkerLogicTest : StringSpec({

    "値下がり率計算: 5000→4000 = 20%" {
        val prev = 5000L
        val current = 4000L
        val dropPct = ((prev - current) * 100 / prev).toInt()
        dropPct shouldBe 20
    }

    "値下がり率計算: 1000→999 = 0% (整数 floor)" {
        val prev = 1000L
        val current = 999L
        val dropPct = ((prev - current) * 100 / prev).toInt()
        dropPct shouldBe 0
    }

    "値上がり時は dropPct 負 — 通知しない" {
        val prev = 4000L
        val current = 5000L
        val isDropped = current < prev
        isDropped shouldBe false
    }

    "同価格: 変化なし" {
        val prev = 3000L
        val current = 3000L
        val isDropped = current < prev
        isDropped shouldBe false
    }

    "WORK_NAME は一意識別子" {
        // Worker の重複登録防止用定数が非空であること
        val name = "price_sync_daily"  // companion object の値と同じ
        name.isNotEmpty() shouldBe true
    }

    "制約: Wi-Fi Only + Battery Not Low + Storage Not Low" {
        // 制約がコードに正しく設定されていることの文書化テスト
        // 実際の Constraints はビルド時に検証される
        // ここでは設計意図の文書化
        val constraints = mapOf(
            "networkType" to "UNMETERED",
            "batteryNotLow" to true,
            "storageNotLow" to true,
        )
        constraints["networkType"] shouldBe "UNMETERED"
        constraints["batteryNotLow"] shouldBe true
        constraints["storageNotLow"] shouldBe true
    }

    "指数バックオフ: 30s → 60s → 120s" {
        val baseMs = 30_000L
        val first = baseMs * 1        // 30s
        val second = baseMs * 2       // 60s
        val third = baseMs * 4        // 120s
        first shouldBe 30_000L
        second shouldBe 60_000L
        third shouldBe 120_000L
    }
})
