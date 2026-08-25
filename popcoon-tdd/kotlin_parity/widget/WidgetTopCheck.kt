package io.github.shizukutanaka.popcoon.feature.watchlist

/**
 * WidgetVerdict.topForWidget の並び・切り出しを **実行して** 検証する。
 *
 * Python オラクルは持たない (ウィジェット固有の表示規則であり二重管理する意味が無い) が、
 * 「上限を掛ける前に優先順位を付ける」という不変条件は純ロジックなので
 * Android SDK 無しに実行検証できる。
 *
 * 回帰の中身: 以前は `items.take(3)` で WatchlistDao の `ORDER BY addedAt DESC` のまま
 * 先頭 3 件を出していたため、4 件以上ウォッチしていると目標価格に到達した項目が
 * 「最近追加した 3 件」に押し出されてホーム画面から消えていた。
 */

private var checks = 0

private fun assert(cond: Boolean, msg: String) {
    checks++
    if (!cond) {
        System.err.println("ASSERTION FAILED: $msg")
        System.exit(1)
    }
}

/** テスト用の項目。DAO の並び (addedAt DESC) で渡ってくる想定。 */
private data class Item(
    val name: String,
    val realPrice: Long,
    val targetPrice: Long?,
    val addedPrice: Long,
)

private fun cand(i: Item) = WidgetVerdict.Candidate(i.realPrice, i.targetPrice, i.addedPrice)

private fun top(items: List<Item>, limit: Int = WidgetVerdict.WIDGET_ITEM_LIMIT) =
    WidgetVerdict.topForWidget(items, limit) { cand(it) }

private fun verdict(i: Item) = WidgetVerdict.forItem(i.realPrice, i.targetPrice, i.addedPrice)

fun main() {
    // ── 1. 回帰: 目標到達が「最近追加した 3 件」に押し出されない ────────────────
    // DAO 順 (addedAt DESC) では target 到達品が 4 番目。旧実装では表示されなかった。
    val watchlist = listOf(
        Item("newest",     realPrice = 10_000, targetPrice = null,  addedPrice = 10_000), // NEUTRAL
        Item("second",     realPrice = 20_000, targetPrice = null,  addedPrice = 20_000), // NEUTRAL
        Item("third",      realPrice = 30_000, targetPrice = null,  addedPrice = 30_000), // NEUTRAL
        Item("target-hit", realPrice = 7_000,  targetPrice = 8_000, addedPrice = 9_000),  // BUY_NOW
    )
    assert(watchlist.take(3).none { verdict(it) == WidgetVerdict.BUY_NOW },
        "フィクスチャ不正: 旧実装 (先頭 3 件) に BUY_NOW が入ってしまっている")
    val picked = top(watchlist)
    assert(picked.first().name == "target-hit",
        "目標到達品が先頭でない: ${picked.map { it.name }}")
    assert(picked.size == 3, "件数が 3 でない: ${picked.size}")

    // ── 2. BUY_NOW が常に非 BUY_NOW より前 ────────────────────────────────────
    val mixed = listOf(
        Item("neutral-a", 10_000, null, 10_000),
        Item("buy-small", 9_500, null, 10_000),   // -5% → BUY_NOW
        Item("wait",      11_000, null, 10_000),  // +10% → WAIT
        Item("buy-big",   5_000, null, 10_000),   // -50% → BUY_NOW
        Item("neutral-b", 10_100, null, 10_000),  // +1% → NEUTRAL
    )
    val orderedAll = top(mixed, limit = mixed.size)
    val ranks = orderedAll.map { if (verdict(it) == WidgetVerdict.BUY_NOW) 0 else 1 }
    assert(ranks == ranks.sorted(), "BUY_NOW が後ろに回っている: ${orderedAll.map { it.name }}")
    // BUY_NOW 内は下落率の大きい順
    assert(orderedAll.take(2).map { it.name } == listOf("buy-big", "buy-small"),
        "BUY_NOW 内が下落率順でない: ${orderedAll.take(2).map { it.name }}")

    // ── 3. 安定性: 同順位は入力順 (= addedAt DESC) を保つ ──────────────────────
    val ties = listOf(
        Item("t1", 10_000, null, 10_000),
        Item("t2", 20_000, null, 20_000),
        Item("t3", 30_000, null, 30_000),
    )
    assert(top(ties, limit = 3).map { it.name } == listOf("t1", "t2", "t3"),
        "同順位で入力順が崩れた")

    // ── 4. ¥0 汚染レコードが最上位に来ない ────────────────────────────────────
    // realPrice=0 は取得失敗。100% 下落として先頭に並ぶと「買い時」を誤報する。
    val polluted = listOf(
        Item("zero",  0,     null,  10_000),   // 汚染 → NEUTRAL / dropPercent 0
        Item("real",  9_000, null,  10_000),   // -10% → BUY_NOW
    )
    assert(top(polluted, limit = 2).map { it.name } == listOf("real", "zero"),
        "¥0 レコードが実データより前に並んだ")
    assert(WidgetVerdict.dropPercent(WidgetVerdict.Candidate(0, null, 10_000)) == 0,
        "¥0 の下落率が 0 でない")

    // ── 5. 値上がりは負の下落率にしない (順序が反転しないこと) ──────────────────
    assert(WidgetVerdict.dropPercent(WidgetVerdict.Candidate(12_000, null, 10_000)) == 0,
        "値上がりで負値が出ている")

    // ── 6. limit の境界 ──────────────────────────────────────────────────────
    assert(top(mixed, limit = 0).isEmpty(), "limit=0 が空でない")
    assert(top(mixed, limit = -5).isEmpty(), "負の limit が空でない")
    assert(top(mixed, limit = 100).size == mixed.size, "limit > 件数で全件にならない")
    assert(top(emptyList(), limit = 3).isEmpty(), "空入力が空でない")

    // ── 7. 結果は必ず入力の部分集合・重複なし ─────────────────────────────────
    for (limit in 0..mixed.size + 2) {
        val out = top(mixed, limit)
        assert(out.size == minOf(limit.coerceAtLeast(0), mixed.size), "件数が min(limit, n) でない")
        assert(out.map { it.name }.toSet().size == out.size, "重複がある")
        assert(out.all { it in mixed }, "入力に無い項目が出た")
    }

    println("WIDGET TOP: all assertions passed ($checks checks)")
}
