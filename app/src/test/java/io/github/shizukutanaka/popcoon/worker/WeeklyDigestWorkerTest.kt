package io.github.shizukutanaka.popcoon.worker

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WeeklyDigestWorkerTest : StringSpec({

    "addedPrice > realPrice は値下がり対象" {
        WeeklyDigestWorker.dropCountFrom(listOf(4000L to 5000L)) shouldBe 1
    }

    "addedPrice == realPrice は値下がりではない" {
        WeeklyDigestWorker.dropCountFrom(listOf(5000L to 5000L)) shouldBe 0
    }

    "addedPrice < realPrice (値上がり) は値下がりではない" {
        WeeklyDigestWorker.dropCountFrom(listOf(6000L to 5000L)) shouldBe 0
    }

    "addedPrice == 0 は基準なし (v3以前) として除外" {
        WeeklyDigestWorker.dropCountFrom(listOf(0L to 0L, 4000L to 0L)) shouldBe 0
    }

    "複数件の混在: 値下がりのみをカウント" {
        val pairs = listOf(
            4000L to 5000L,  // drop ✓
            6000L to 5000L,  // up ✗
            3000L to 3000L,  // same ✗
            2000L to 0L,     // no baseline ✗
            1000L to 1500L,  // drop ✓
        )
        WeeklyDigestWorker.dropCountFrom(pairs) shouldBe 2
    }

    "空リストは 0" {
        WeeklyDigestWorker.dropCountFrom(emptyList()) shouldBe 0
    }

    "WORK_NAME は 'weekly_digest' (WorkManager スケジュール一意識別子)" {
        WeeklyDigestWorker.WORK_NAME shouldBe "weekly_digest"
    }
})
