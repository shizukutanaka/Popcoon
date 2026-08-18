package io.github.shizukutanaka.popcoon.ui.components

import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * PriceChart の純関数テスト (filterByRange / plottableRecords)。
 * 期間切替 (1週/1ヶ月/全期間) の絞り込みと、描画対象レコードの選別を
 * 本番呼び出しで直接検証する。
 */
class PriceChartTest : StringSpec({

    fun recordDaysAgo(days: Long): PriceRecord = PriceRecord(
        productKey = "p", platform = "amazon",
        listPrice = 5000, realPrice = 4000,
        recordedAt = Instant.now().minusSeconds(days * 86_400),
    )

    "ALL は絞り込まず全件を返す" {
        val records = listOf(recordDaysAgo(1), recordDaysAgo(10), recordDaysAgo(100))
        filterByRange(records, PriceChartRange.ALL) shouldHaveSize 3
    }

    "WEEK (7日) は直近7日以内のみ返す" {
        val records = listOf(recordDaysAgo(1), recordDaysAgo(6), recordDaysAgo(8), recordDaysAgo(30))
        filterByRange(records, PriceChartRange.WEEK) shouldHaveSize 2
    }

    "MONTH (30日) は直近30日以内のみ返す" {
        val records = listOf(recordDaysAgo(1), recordDaysAgo(29), recordDaysAgo(31), recordDaysAgo(100))
        filterByRange(records, PriceChartRange.MONTH) shouldHaveSize 2
    }

    "該当レコードが無ければ空リストを返す (例外なし)" {
        val records = listOf(recordDaysAgo(100), recordDaysAgo(200))
        filterByRange(records, PriceChartRange.WEEK) shouldHaveSize 0
    }

    "空リストを渡しても空リストを返す" {
        filterByRange(emptyList(), PriceChartRange.WEEK) shouldBe emptyList()
    }

    "PriceChartRange.days: WEEK=7, MONTH=30, ALL=null" {
        PriceChartRange.WEEK.days shouldBe 7
        PriceChartRange.MONTH.days shouldBe 30
        PriceChartRange.ALL.days shouldBe null
    }

    // ── plottableRecords: ¥0 汚染の除外 + 時系列ソート ──────────────────
    fun recordAt(days: Long, price: Long): PriceRecord = PriceRecord(
        productKey = "p", platform = "amazon",
        listPrice = 5000, realPrice = price,
        recordedAt = Instant.now().minusSeconds(days * 86_400),
    )

    "plottableRecords: realPrice <= 0 は描画対象から外す" {
        val records = listOf(recordAt(3, 4000), recordAt(2, 0), recordAt(1, 3800))
        val plotted = plottableRecords(records)
        plotted shouldHaveSize 2
        plotted.map { it.realPrice } shouldBe listOf(4000L, 3800L)
    }

    "plottableRecords: ¥0 が混ざっても最安値が 0 に落ちない" {
        val records = listOf(recordAt(3, 4000), recordAt(2, 0), recordAt(1, 3800))
        plottableRecords(records).minOf { it.realPrice } shouldBe 3800L
    }

    "plottableRecords: 記録時刻の昇順に並べ替える" {
        val records = listOf(recordAt(1, 3800), recordAt(3, 4000), recordAt(2, 3900))
        plottableRecords(records).map { it.realPrice } shouldBe listOf(4000L, 3900L, 3800L)
    }

    "plottableRecords: 全件が ¥0 なら空 (グラフは『データ不足』表示になる)" {
        plottableRecords(listOf(recordAt(2, 0), recordAt(1, 0))) shouldBe emptyList()
    }

    "plottableRecords: 負の価格も除外する" {
        plottableRecords(listOf(recordAt(2, -100), recordAt(1, 3800)))
            .map { it.realPrice } shouldBe listOf(3800L)
    }
})
