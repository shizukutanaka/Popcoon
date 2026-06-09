package com.example.popcoon.feature.watchlist

import com.example.popcoon.data.db.WatchlistItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

class WatchlistSortTest : StringSpec({

    fun item(
        key: String,
        title: String = key,
        price: Long = 1000,
        listPrice: Long = price,
        addedAt: Long = 0,
        target: Long? = null,
    ) = WatchlistItem(
        productKey = key,
        sku = key,
        title = title,
        platform = "amazon",
        realPrice = price,
        listPrice = listPrice,
        url = "",
        imageUrl = null,
        addedAt = addedAt,
        targetPrice = target,
    )

    fun keys(items: List<WatchlistItem>) = items.map { it.productKey }

    // ── ADDED_DESC（既定） ──────────────────────────────────────────────────
    "ADDED_DESC は追加日が新しい順" {
        val items = listOf(
            item("a", addedAt = 100),
            item("b", addedAt = 300),
            item("c", addedAt = 200),
        )
        keys(WatchlistSort.sort(items, WatchlistSort.Mode.ADDED_DESC)) shouldBe
            listOf("b", "c", "a")
    }

    // ── PRICE ───────────────────────────────────────────────────────────────
    "PRICE_ASC は安い順" {
        val items = listOf(
            item("a", price = 3000),
            item("b", price = 1000),
            item("c", price = 2000),
        )
        keys(WatchlistSort.sort(items, WatchlistSort.Mode.PRICE_ASC)) shouldBe
            listOf("b", "c", "a")
    }

    "PRICE_DESC は高い順" {
        val items = listOf(
            item("a", price = 3000),
            item("b", price = 1000),
            item("c", price = 2000),
        )
        keys(WatchlistSort.sort(items, WatchlistSort.Mode.PRICE_DESC)) shouldBe
            listOf("a", "c", "b")
    }

    "同価格は追加日が新しい順でタイブレーク" {
        val items = listOf(
            item("a", price = 1000, addedAt = 100),
            item("b", price = 1000, addedAt = 300),
        )
        keys(WatchlistSort.sort(items, WatchlistSort.Mode.PRICE_ASC)) shouldBe
            listOf("b", "a")
    }

    // ── DISCOUNT ──────────────────────────────────────────────────────────────
    "DISCOUNT_DESC は割引率が大きい順" {
        val items = listOf(
            item("a", price = 900, listPrice = 1000),   // 10%
            item("b", price = 500, listPrice = 1000),   // 50%
            item("c", price = 1000, listPrice = 1000),  // 0%
        )
        keys(WatchlistSort.sort(items, WatchlistSort.Mode.DISCOUNT_DESC)) shouldBe
            listOf("b", "a", "c")
    }

    "DISCOUNT_DESC: listPrice <= realPrice は割引 0 扱い" {
        val items = listOf(
            item("a", price = 1200, listPrice = 1000),  // 異常: 参考価格より高い → 0
            item("b", price = 800, listPrice = 1000),   // 20%
        )
        keys(WatchlistSort.sort(items, WatchlistSort.Mode.DISCOUNT_DESC)) shouldBe
            listOf("b", "a")
    }

    // ── NAME ────────────────────────────────────────────────────────────────
    "NAME_ASC は名前昇順（大小無視）" {
        val items = listOf(
            item("a", title = "Zebra"),
            item("b", title = "apple"),
            item("c", title = "Mango"),
        )
        keys(WatchlistSort.sort(items, WatchlistSort.Mode.NAME_ASC)) shouldBe
            listOf("b", "c", "a")
    }

    // ── TARGET_PROGRESS ───────────────────────────────────────────────────────
    "TARGET_PROGRESS: 目標到達 → 近い順、未設定は末尾" {
        val items = listOf(
            item("noTarget", price = 500, target = null),
            item("reached", price = 800, target = 1000),  // ratio 0.8（到達）
            item("far", price = 2000, target = 1000),     // ratio 2.0
            item("near", price = 1100, target = 1000),    // ratio 1.1
        )
        keys(WatchlistSort.sort(items, WatchlistSort.Mode.TARGET_PROGRESS)) shouldBe
            listOf("reached", "near", "far", "noTarget")
    }

    "TARGET_PROGRESS: target が 0 以下は未設定扱い" {
        val items = listOf(
            item("zero", price = 500, target = 0),
            item("valid", price = 900, target = 1000),
        )
        keys(WatchlistSort.sort(items, WatchlistSort.Mode.TARGET_PROGRESS)) shouldBe
            listOf("valid", "zero")
    }

    // ── 不変条件 ────────────────────────────────────────────────────────────
    "空リストはどのモードでも空" {
        WatchlistSort.Mode.entries.forEach { mode ->
            WatchlistSort.sort(emptyList(), mode) shouldBe emptyList()
        }
    }

    "ソートは要素を増減させない（順列保存）" {
        checkAll(
            Arb.list(Arb.element((1..20).toList()), 0..15),
        ) { ints ->
            val items = ints.mapIndexed { i, p -> item("k$i", price = p.toLong()) }
            WatchlistSort.Mode.entries.forEach { mode ->
                val sorted = WatchlistSort.sort(items, mode)
                sorted.size shouldBe items.size
                sorted.map { it.productKey }.toSet() shouldBe items.map { it.productKey }.toSet()
            }
        }
    }

    "ソートは決定的（同入力で同出力）" {
        val items = listOf(
            item("a", price = 1000, addedAt = 5),
            item("b", price = 1000, addedAt = 5),
            item("c", price = 1000, addedAt = 5),
        )
        WatchlistSort.Mode.entries.forEach { mode ->
            val first = keys(WatchlistSort.sort(items, mode))
            val second = keys(WatchlistSort.sort(items.shuffled(), mode))
            first shouldBe second
        }
    }
})
