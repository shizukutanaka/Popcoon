package com.example.popcoon.ui.screens.search

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import com.example.popcoon.feature.scorer.BuyTimingScorer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class SortAndFilterTest : StringSpec({

    fun mkRow(
        sku: String,
        price: Long,
        platform: Platform = Platform.AMAZON,
        score: Int = 50,
        rating: Float? = null,
        listPrice: Long = price,
        title: String = sku,
        stockCount: Int? = null,
        effectivePrice: Long = price,
    ): SearchRow {
        return SearchRow(
            product = Product(
                sku = sku, title = title, platform = platform,
                realPrice = price, listPrice = listPrice,
                rating = rating, stockCount = stockCount,
            ),
            verdict = BuyTimingScorer.Verdict.NEUTRAL,
            warnings = emptyList(),
            score = score,
            effectivePrice = effectivePrice,
        )
    }

    // ── SortOption ───────────────────────────────────────────────────────
    "BUY_TIMING: スコア降順で並べ替え" {
        val rows = listOf(mkRow("a", 1000, score = 30), mkRow("b", 1000, score = 80), mkRow("c", 1000, score = 50))
        val sorted = SortOption.apply(rows, SortOption.BUY_TIMING)
        sorted.map { it.product.sku } shouldBe listOf("b", "c", "a")
    }

    "PRICE_ASC: 価格昇順" {
        val rows = listOf(mkRow("a", 3000), mkRow("b", 1000), mkRow("c", 2000))
        val sorted = SortOption.apply(rows, SortOption.PRICE_ASC)
        sorted.map { it.product.sku } shouldBe listOf("b", "c", "a")
    }

    "PRICE_DESC: 価格降順" {
        val rows = listOf(mkRow("a", 3000), mkRow("b", 1000), mkRow("c", 2000))
        val sorted = SortOption.apply(rows, SortOption.PRICE_DESC)
        sorted.map { it.product.sku } shouldBe listOf("a", "c", "b")
    }

    // ── effectivePrice (ポイント還元後) で並ぶことの回帰防止 ───────────────
    // 重要: sticker (totalPrice) 順と effectivePrice 順が *食い違う* データを使う。
    // これにより SortAndFilter が誤って totalPrice に戻された場合にこのテストが落ちる。
    // (従来の PRICE_ASC テストは effectivePrice==totalPrice のため両実装で通り、保護にならない)
    "PRICE_ASC: sticker ではなく effectivePrice で並べ替え" {
        val rows = listOf(
            // sticker 順: b(995) < a(1000) < c(1010)
            // effective 順: c(900) < a(990) < b(995)  ← ポイント還元でクロスする
            mkRow("a", 1000, effectivePrice = 990),
            mkRow("b", 995, effectivePrice = 995),
            mkRow("c", 1010, effectivePrice = 900),
        )
        val sorted = SortOption.apply(rows, SortOption.PRICE_ASC)
        sorted.map { it.product.sku } shouldBe listOf("c", "a", "b")
    }

    "PRICE_DESC: sticker ではなく effectivePrice で並べ替え" {
        val rows = listOf(
            mkRow("a", 1000, effectivePrice = 990),
            mkRow("b", 995, effectivePrice = 995),
            mkRow("c", 1010, effectivePrice = 900),
        )
        val sorted = SortOption.apply(rows, SortOption.PRICE_DESC)
        sorted.map { it.product.sku } shouldBe listOf("b", "a", "c")
    }

    "DISCOUNT_DESC: 割引率の高い順" {
        val rows = listOf(
            mkRow("a", 800, listPrice = 1000),    // 20% OFF
            mkRow("b", 500, listPrice = 1000),    // 50% OFF
            mkRow("c", 900, listPrice = 1000),    // 10% OFF
        )
        val sorted = SortOption.apply(rows, SortOption.DISCOUNT_DESC)
        sorted.map { it.product.sku } shouldBe listOf("b", "a", "c")
    }

    "RATING_DESC: 評価の高い順 (null は最後)" {
        val rows = listOf(
            mkRow("a", 1000, rating = 4.0f),
            mkRow("b", 1000, rating = null),
            mkRow("c", 1000, rating = 5.0f),
        )
        val sorted = SortOption.apply(rows, SortOption.RATING_DESC)
        sorted.first().product.sku shouldBe "c"
        sorted.last().product.sku shouldBe "b"
    }

    // ── SearchFilter ─────────────────────────────────────────────────────
    "デフォルトフィルタは在庫切れだけ除外" {
        val rows = listOf(
            mkRow("a", 1000, stockCount = 5),
            mkRow("b", 1000, stockCount = 0),       // 在庫切れ
            mkRow("c", 1000, stockCount = null),    // 不明
        )
        val filtered = SearchFilter().apply(rows)
        filtered.map { it.product.sku } shouldBe listOf("a", "c")
    }

    "キーワード除外: 中古を含む商品を除外" {
        val rows = listOf(
            mkRow("a", 1000, title = "新品 iPhone 15"),
            mkRow("b", 1000, title = "中古 iPhone 14"),
            mkRow("c", 1000, title = "訳あり iPad mini"),
        )
        val filter = SearchFilter(excludeKeywordsEnabled = true)
        val filtered = filter.apply(rows)
        filtered.map { it.product.sku } shouldBe listOf("a")
        filtered.map { it.product.sku } shouldNotContain "b"
        filtered.map { it.product.sku } shouldNotContain "c"
    }

    "最低評価フィルター: 4.0 未満を除外" {
        val rows = listOf(
            mkRow("a", 1000, rating = 4.5f),
            mkRow("b", 1000, rating = 3.5f),
            mkRow("c", 1000, rating = null),
        )
        val filter = SearchFilter(minRating = 4.0f)
        val filtered = filter.apply(rows)
        filtered.map { it.product.sku } shouldBe listOf("a")
    }

    "価格範囲フィルター: 上下限指定" {
        val rows = listOf(
            mkRow("a", 500),
            mkRow("b", 1500),
            mkRow("c", 5000),
        )
        val filter = SearchFilter(minPrice = 1000, maxPrice = 3000)
        val filtered = filter.apply(rows)
        filtered.map { it.product.sku } shouldBe listOf("b")
    }

    // 回帰防止: 価格範囲フィルタは sticker ではなく effectivePrice で判定する。
    // sticker では枠外、effectivePrice ではポイント還元で枠内に入る商品を残すこと。
    "価格範囲フィルター: effectivePrice で判定 (sticker 枠外でも還元後は枠内)" {
        val rows = listOf(
            // sticker 3200 は maxPrice=3000 を超えるが、effective 2900 なら枠内に残すべき
            mkRow("a", 3200, effectivePrice = 2900),
            // sticker 2800 は枠内だが、ここでは effective も枠内 (対照)
            mkRow("b", 2800, effectivePrice = 2800),
            // sticker 2900 だが effective 3100 で上限超え → 除外されるべき
            mkRow("c", 2900, effectivePrice = 3100),
        )
        val filter = SearchFilter(minPrice = 1000, maxPrice = 3000)
        val filtered = filter.apply(rows).map { it.product.sku }
        filtered shouldBe listOf("a", "b")
        filtered shouldNotContain "c"
    }

    "プラットフォーム絞り込み: Amazon のみ" {
        val rows = listOf(
            mkRow("a", 1000, platform = Platform.AMAZON),
            mkRow("b", 1000, platform = Platform.RAKUTEN),
            mkRow("c", 1000, platform = Platform.YAHOO),
        )
        val filter = SearchFilter(platforms = setOf(Platform.AMAZON))
        val filtered = filter.apply(rows)
        filtered.map { it.product.sku } shouldBe listOf("a")
    }

    "isAnyFilterActive: デフォルトは false" {
        SearchFilter().isAnyFilterActive shouldBe false
    }

    "isAnyFilterActive: キーワード除外 ON で true" {
        SearchFilter(excludeKeywordsEnabled = true).isAnyFilterActive shouldBe true
    }

    "isAnyFilterActive: 最低評価設定で true" {
        SearchFilter(minRating = 4.0f).isAnyFilterActive shouldBe true
    }

    "全フィルタ + ソートを組み合わせ" {
        val rows = listOf(
            mkRow("a", 800, listPrice = 1000, rating = 4.5f, title = "新品 A"),
            mkRow("b", 1500, listPrice = 2000, rating = 4.8f, title = "中古 B"),
            mkRow("c", 1200, listPrice = 1500, rating = 3.0f, title = "新品 C"),
            mkRow("d", 600, listPrice = 1000, rating = 4.2f, title = "新品 D"),
        )
        // フィルタ: 中古除外 + 評価4.0以上
        val filter = SearchFilter(excludeKeywordsEnabled = true, minRating = 4.0f)
        val filtered = filter.apply(rows)
        // ソート: 割引率の高い順
        val sorted = SortOption.apply(filtered, SortOption.DISCOUNT_DESC)
        sorted.map { it.product.sku } shouldBe listOf("d", "a")
    }
})
