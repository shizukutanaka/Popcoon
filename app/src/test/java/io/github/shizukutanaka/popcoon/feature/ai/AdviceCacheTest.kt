package io.github.shizukutanaka.popcoon.feature.ai

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class AdviceCacheTest : StringSpec({

    fun mkProduct(sku: String = "B0TEST"): Product = Product(
        sku = sku, title = "テスト", platform = Platform.AMAZON,
        realPrice = 1000, listPrice = 1500,
    )

    fun mkScore(total: Int = 75, verdict: BuyTimingScorer.Verdict = BuyTimingScorer.Verdict.BUY_NOW) =
        BuyTimingScorer.Score(
            total = total, verdict = verdict,
            confidence = "高", signals = emptyList(),
        )

    "新規キャッシュは null を返す" {
        val cache = AdviceCache()
        cache.get(mkProduct(), mkScore()).shouldBeNull()
    }

    "put 後に同じキーで get → ヒット" {
        val cache = AdviceCache()
        val product = mkProduct()
        val score = mkScore()
        cache.put(product, score, "今が買い時")
        cache.get(product, score) shouldBe "今が買い時"
    }

    "違う商品はキャッシュ別" {
        val cache = AdviceCache()
        cache.put(mkProduct("A"), mkScore(), "助言A")
        cache.put(mkProduct("B"), mkScore(), "助言B")
        cache.get(mkProduct("A"), mkScore()) shouldBe "助言A"
        cache.get(mkProduct("B"), mkScore()) shouldBe "助言B"
    }

    "スコアが10以内ならキャッシュ共有 (バケット 7)" {
        val cache = AdviceCache()
        val p = mkProduct()
        cache.put(p, mkScore(75), "今が買い時")
        // 76, 77, 78, 79 は同じバケット (7)
        cache.get(p, mkScore(76)).shouldNotBeNull()
        cache.get(p, mkScore(79)).shouldNotBeNull()
        cache.get(p, mkScore(70)).shouldNotBeNull()
    }

    "スコアバケット境界を跨ぐとキャッシュミス" {
        val cache = AdviceCache()
        val p = mkProduct()
        cache.put(p, mkScore(79), "今が買い時")
        // 80 は異なるバケット (8)
        cache.get(p, mkScore(80)).shouldBeNull()
    }

    "verdict が違うとキャッシュミス" {
        val cache = AdviceCache()
        val p = mkProduct()
        cache.put(p, mkScore(75, BuyTimingScorer.Verdict.BUY_NOW), "買い時")
        cache.get(p, mkScore(75, BuyTimingScorer.Verdict.WAIT)).shouldBeNull()
    }

    "clear で全削除" {
        val cache = AdviceCache()
        cache.put(mkProduct("A"), mkScore(), "x")
        cache.put(mkProduct("B"), mkScore(), "y")
        cache.clear()
        cache.size() shouldBe 0
        cache.get(mkProduct("A"), mkScore()).shouldBeNull()
    }

    "100件超で LRU で古いものが追い出される" {
        val cache = AdviceCache()
        // 最大サイズは 100
        repeat(101) { i ->
            cache.put(mkProduct("SKU$i"), mkScore(), "advice$i")
        }
        cache.size() shouldBe 100
    }

    // 回帰: 旧実装は作成時刻ベースの FIFO で、直近アクセスされたエントリでも
    // 最古なら追い出されていた。真の LRU なら「最近 get() された」エントリは生き残る
    // (機能過不足監査で発見: ドキュメント上の "LRU" と実装が食い違っていた)。
    //
    // ⚠️ フィクスチャの順序が本体。以前は put(SKU0) → get(SKU0) → 100件 put の順で、
    // アクセス後に 100 件挿入されるため **LRU でも FIFO でも SKU0 が最古**になり、
    // 両方式を区別できないうえ「SKU0 が残る」という誤った結果を主張していた
    // (kotest シム run_kotest.sh で初めて実行して発覚 — 真の LRU 実装に対して落ちる)。
    // 判別できる順序は「満杯にしてから最古候補を触り、その後に 1 件足す」:
    //   LRU  → 触った SKU0 は最新扱いになり、SKU1 が追い出される
    //   FIFO → 作成が最古の SKU0 が追い出される
    "満杯直前に get したエントリは追い出されない (真の LRU 判別)" {
        val cache = AdviceCache()
        val p0 = mkProduct("SKU0")
        val p1 = mkProduct("SKU1")
        cache.put(p0, mkScore(), "advice0")
        // SKU1..SKU99 を追加して満杯 (100 件) にする
        repeat(99) { i ->
            cache.put(mkProduct("SKU${i + 1}"), mkScore(), "advice${i + 1}")
        }
        cache.size() shouldBe 100
        // 最古候補 SKU0 をここでアクセスして「最近使った」扱いにする
        cache.get(p0, mkScore()).shouldNotBeNull()
        // 101 件目 → 1 件追い出し
        cache.put(mkProduct("SKU100"), mkScore(), "advice100")
        cache.size() shouldBe 100
        // LRU: 直近アクセス済みの SKU0 は生存し、次に古い SKU1 が追い出される
        cache.get(p0, mkScore()) shouldBe "advice0"
        cache.get(p1, mkScore()).shouldBeNull()
    }
})
