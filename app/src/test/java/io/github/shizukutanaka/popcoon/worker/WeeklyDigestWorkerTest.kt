package io.github.shizukutanaka.popcoon.worker

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WeeklyDigestWorkerTest : StringSpec({

    "addedPrice > realPrice は値下がり対象" {
        WeeklyDigestLogic.dropCountFrom(listOf(4000L to 5000L)) shouldBe 1
    }

    "addedPrice == realPrice は値下がりではない" {
        WeeklyDigestLogic.dropCountFrom(listOf(5000L to 5000L)) shouldBe 0
    }

    "addedPrice < realPrice (値上がり) は値下がりではない" {
        WeeklyDigestLogic.dropCountFrom(listOf(6000L to 5000L)) shouldBe 0
    }

    "addedPrice == 0 は基準なし (v3以前) として除外" {
        WeeklyDigestLogic.dropCountFrom(listOf(0L to 0L, 4000L to 0L)) shouldBe 0
    }

    // ¥0 汚染 (取得失敗を 0 円として記録したレコード) は「値下がり」ではない。
    // realPrice > 0 のガードが無いと 0 < addedPrice が常に成立し、ダイジェストの
    // 件数が実態より水増しされる (BuyTimingScorer で実際に判定を反転させたのと同じ欠陥)。
    "realPrice == 0 (取得失敗の汚染レコード) は値下がりに数えない" {
        WeeklyDigestLogic.dropCountFrom(listOf(0L to 5000L)) shouldBe 0
    }

    "realPrice が負 (異常値) も値下がりに数えない" {
        WeeklyDigestLogic.dropCountFrom(listOf(-100L to 5000L)) shouldBe 0
    }

    "複数件の混在: 値下がりのみをカウント" {
        val pairs = listOf(
            4000L to 5000L,  // drop ✓
            6000L to 5000L,  // up ✗
            3000L to 3000L,  // same ✗
            2000L to 0L,     // no baseline ✗
            0L to 4000L,     // ¥0 汚染 ✗
            1000L to 1500L,  // drop ✓
        )
        WeeklyDigestLogic.dropCountFrom(pairs) shouldBe 2
    }

    "空リストは 0" {
        WeeklyDigestLogic.dropCountFrom(emptyList()) shouldBe 0
    }

    "WORK_NAME は 'weekly_digest' (WorkManager スケジュール一意識別子)" {
        WorkNames.WEEKLY_DIGEST shouldBe "weekly_digest"
    }
})
