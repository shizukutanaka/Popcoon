package io.github.shizukutanaka.popcoon.feature.calendar

import io.github.shizukutanaka.popcoon.data.model.Platform
import java.time.LocalDate

/**
 * SaleCalendar の順序・網羅性を **実行して** 検証する。
 *
 * SaleCalendar は Python オラクルを持たない (日付固定の外部仕様データのため
 * 二重管理する意味が無い) が、`activeSales` の並び順や `nextMajorSale` /
 * `upcomingSales` の不変条件は純ロジックで、Android SDK 無しに実行検証できる。
 *
 * この harness を足した理由: kotest 側の「活性セールリストは tier 降順」が
 * **2026-07-17 (金曜・17日)** を使っており、この日は monthlyRecurring が 1 件も
 * 返さない (5のつく日でも 5と0でも 11/22 でも日曜でもない)。つまり活性セールは
 * プライムデー 1 件だけで、`first().tier == MAJOR` は並び順に関係なく成立していた。
 * テスト名が宣言する不変条件をフィクスチャが一度も踏んでいない状態で、
 * 実際には `sortedByDescending { it.tier.ordinal }` により RECURRING が先頭に
 * 来ていた (Tier は MAJOR, MEDIUM, RECURRING の重要度順に宣言されているので
 * ordinal の降順 = 重要度の昇順)。
 * ここではフィクスチャが本当に両 tier を含むことも同時に表明する。
 */

private var checks = 0

private fun assert(cond: Boolean, msg: String) {
    checks++
    if (!cond) {
        System.err.println("ASSERTION FAILED: $msg")
        System.exit(1)
    }
}

private fun daysOfYear(year: Int): List<LocalDate> {
    val start = LocalDate.of(year, 1, 1)
    return (0 until (if (LocalDate.of(year, 12, 31).dayOfYear == 366) 366 else 365))
        .map { start.plusDays(it.toLong()) }
}

fun main() {
    val year = 2026
    val allDays = daysOfYear(year)

    // ── 1. activeSales は重要度の高い順 (MAJOR → MEDIUM → RECURRING) ──────────
    // Tier は重要度順に宣言されているので ordinal 昇順が正しい並び。
    var daysWithBothTiers = 0
    for (d in allDays) {
        val sales = SaleCalendar.activeSales(d)
        val ordinals = sales.map { it.tier.ordinal }
        assert(ordinals == ordinals.sorted(),
            "activeSales($d) が重要度順でない: ${sales.map { it.tier }}")

        val hasMajorOrMedium = sales.any { it.tier != SaleCalendar.Tier.RECURRING }
        val hasRecurring = sales.any { it.tier == SaleCalendar.Tier.RECURRING }
        if (hasMajorOrMedium && hasRecurring) {
            daysWithBothTiers++
            assert(sales.first().tier != SaleCalendar.Tier.RECURRING,
                "activeSales($d) の先頭が RECURRING: ${sales.map { it.name }}")
        }
    }
    // フィクスチャの有効性そのものを表明する。これが 0 なら上の順序検査は空回りで、
    // まさにそれが kotest 側で起きていた欠陥。2026 年の実測は 27 日
    // (非 RECURRING が活性な 66 日のうち、5/10/11/15/20/22/25/30 日か日曜と重なる日)。
    // 下限は「明らかに空回りでない」ことを担保する 20 に置く。
    assert(daysWithBothTiers >= 20,
        "大型/中型と繰り返しが同時に活性な日が $daysWithBothTiers 日しかない — 検査が空回りしている")

    // ── 2. platform フィルタは横断イベント (platform == null) を落とさない ──────
    for (d in allDays) {
        val all = SaleCalendar.activeSales(d)
        for (p in Platform.entries) {
            val filtered = SaleCalendar.activeSales(d, p)
            val expected = all.filter { it.platform == null || it.platform == p }
            assert(filtered.map { it.name } == expected.map { it.name },
                "activeSales($d, $p) がフィルタ仕様と不一致")
        }
    }

    // ── 3. nextMajorSale: 常に非 null・MAJOR・today 以降・最も近いもの ───────────
    // MAJOR セールは startDate 当日に必ず活性なので、2026+2027 を走査すれば
    // 全 MAJOR 開始日を公開 API だけで集められる。それと突き合わせる。
    val majorStarts = (daysOfYear(year) + daysOfYear(year + 1))
        .flatMap { SaleCalendar.activeSales(it) }
        .filter { it.tier == SaleCalendar.Tier.MAJOR }
        .map { it.startDate }
        .distinct()
        .sorted()
    assert(majorStarts.size >= 8, "MAJOR 開始日が ${majorStarts.size} 件しか集まらない")

    for (d in allDays) {
        val next = SaleCalendar.nextMajorSale(d)
        assert(next != null, "nextMajorSale($d) が null")
        assert(next!!.tier == SaleCalendar.Tier.MAJOR, "nextMajorSale($d) が MAJOR でない")
        assert(!next.startDate.isBefore(d), "nextMajorSale($d) が過去 (${next.startDate})")
        val expected = majorStarts.first { !it.isBefore(d) }
        assert(next.startDate == expected,
            "nextMajorSale($d) = ${next.startDate} だが最も近い MAJOR は $expected")
    }

    // ── 4. upcomingSales: 昇順・today より後・horizon 以内・RECURRING を含まない ─
    for (d in allDays) {
        val up = SaleCalendar.upcomingSales(d, withinDays = 120)
        val dates = up.map { it.startDate }
        assert(dates == dates.sortedBy { it }, "upcomingSales($d) が startDate 昇順でない")
        for (e in up) {
            assert(e.startDate.isAfter(d), "upcomingSales($d) に当日以前の ${e.startDate}")
            assert(!e.startDate.isAfter(d.plusDays(120)), "upcomingSales($d) が horizon 超過")
            assert(e.tier != SaleCalendar.Tier.RECURRING, "upcomingSales($d) に RECURRING")
        }
        assert(up.map { it.name + it.startDate }.toSet().size == up.size,
            "upcomingSales($d) に重複")
    }

    // ── 5. 具体日: 楽天スーパーセール冬 + サイバーマンデー + 日曜 が同時活性 ──────
    // 2026-12-06 は日曜。楽天冬 (12/4-11) とサイバーマンデー (12/6-12) が MAJOR、
    // Yahoo! 日曜 +5% が RECURRING。並び順バグが最も見えやすい日。
    val dec6 = LocalDate.of(year, 12, 6)
    val dec6Sales = SaleCalendar.activeSales(dec6)
    assert(dec6Sales.count { it.tier == SaleCalendar.Tier.MAJOR } == 2,
        "2026-12-06 の MAJOR が 2 件でない: ${dec6Sales.map { it.name }}")
    assert(dec6Sales.any { it.tier == SaleCalendar.Tier.RECURRING },
        "2026-12-06 に RECURRING が無い")
    assert(dec6Sales.first().tier == SaleCalendar.Tier.MAJOR,
        "2026-12-06 の先頭が MAJOR でない: ${dec6Sales.map { "${it.name}(${it.tier})" }}")

    println("SALE CALENDAR: all assertions passed ($checks checks over ${allDays.size} days)")
}
