package com.example.popcoon.feature.watchlist

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

class WatchlistPriceDeltaTest : StringSpec({

    "値下がり → DOWN, 負の額と率" {
        val d = WatchlistPriceDelta.since(addedPrice = 5000, currentPrice = 4000).shouldNotBeNull()
        d.amount shouldBe -1000
        d.percent shouldBe -20
        d.direction shouldBe WatchlistPriceDelta.Direction.DOWN
        d.absAmount shouldBe 1000
        d.absPercent shouldBe 20
    }

    "値上がり → UP, 正の額と率" {
        val d = WatchlistPriceDelta.since(addedPrice = 4000, currentPrice = 5000).shouldNotBeNull()
        d.amount shouldBe 1000
        d.percent shouldBe 25
        d.direction shouldBe WatchlistPriceDelta.Direction.UP
    }

    "横ばい → FLAT, 0" {
        val d = WatchlistPriceDelta.since(addedPrice = 4000, currentPrice = 4000).shouldNotBeNull()
        d.amount shouldBe 0
        d.percent shouldBe 0
        d.direction shouldBe WatchlistPriceDelta.Direction.FLAT
    }

    "率は整数切り捨て" {
        // 3000 → 2900 は -3.33% → -3
        WatchlistPriceDelta.since(3000, 2900)!!.percent shouldBe -3
    }

    "addedPrice が 0 以下 → null（基準なし）" {
        WatchlistPriceDelta.since(0, 4000).shouldBeNull()
        WatchlistPriceDelta.since(-100, 4000).shouldBeNull()
    }

    "currentPrice が 0 以下 → null" {
        WatchlistPriceDelta.since(4000, 0).shouldBeNull()
    }

    "プロパティ: 符号の整合（DOWN なら amount<0, UP なら amount>0）" {
        checkAll(Arb.long(1L..1_000_000L), Arb.long(1L..1_000_000L)) { added, current ->
            val d = WatchlistPriceDelta.since(added, current)!!
            when (d.direction) {
                WatchlistPriceDelta.Direction.DOWN -> (d.amount < 0) shouldBe true
                WatchlistPriceDelta.Direction.UP -> (d.amount > 0) shouldBe true
                WatchlistPriceDelta.Direction.FLAT -> (d.amount == 0L) shouldBe true
            }
        }
    }
})
