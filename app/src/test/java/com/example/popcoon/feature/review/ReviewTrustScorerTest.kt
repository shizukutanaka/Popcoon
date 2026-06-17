package com.example.popcoon.feature.review

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

class ReviewTrustScorerTest : StringSpec({

    "評価なしは UNKNOWN" {
        ReviewTrustScorer.evaluate(null, 100).trust shouldBe ReviewTrustScorer.Trust.UNKNOWN
    }

    "レビュー件数0は UNKNOWN" {
        ReviewTrustScorer.evaluate(4.5f, 0).trust shouldBe ReviewTrustScorer.Trust.UNKNOWN
    }

    "レビュー数が少なすぎる (5未満) は LOW" {
        ReviewTrustScorer.evaluate(5.0f, 3).trust shouldBe ReviewTrustScorer.Trust.LOW
    }

    "大量レビューで満点に近いとサクラ疑い LOW" {
        ReviewTrustScorer.evaluate(4.95f, 2000).trust shouldBe ReviewTrustScorer.Trust.LOW
    }

    "中量レビュー (5-99) は MEDIUM" {
        ReviewTrustScorer.evaluate(4.2f, 50).trust shouldBe ReviewTrustScorer.Trust.MEDIUM
    }

    "十分なレビュー + 自然な評価は HIGH" {
        ReviewTrustScorer.evaluate(4.3f, 500).trust shouldBe ReviewTrustScorer.Trust.HIGH
    }

    "大量レビューでも評価が自然 (4.9未満) なら HIGH" {
        ReviewTrustScorer.evaluate(4.5f, 5000).trust shouldBe ReviewTrustScorer.Trust.HIGH
    }

    "LOW/UNKNOWN には理由キーが付く" {
        ReviewTrustScorer.evaluate(null, 0).reasonKey shouldBe "review_trust_no_data"
        ReviewTrustScorer.evaluate(5.0f, 2).reasonKey shouldBe "review_trust_few_reviews"
        ReviewTrustScorer.evaluate(4.95f, 2000).reasonKey shouldBe "review_trust_too_perfect"
    }

    "HIGH/MEDIUM には理由キーが付かない" {
        ReviewTrustScorer.evaluate(4.3f, 500).reasonKey shouldBe null
        ReviewTrustScorer.evaluate(4.2f, 50).reasonKey shouldBe null
    }

    // 回帰防止: MANY_REVIEWS=1000 境界テスト。片側だけでは境界値 off-by-one を検出できない。
    // guard を 1001 にずらしても「大量レビューで満点は LOW」テストはパスするが、このテストが落ちる。
    "999件+高評価は HIGH (MANY_REVIEWS 境界の識別)" {
        ReviewTrustScorer.evaluate(4.95f, 999).trust shouldBe ReviewTrustScorer.Trust.HIGH
    }
    "1000件+4.9評価は LOW (MANY_REVIEWS 境界の識別)" {
        ReviewTrustScorer.evaluate(4.9f, 1000).trust shouldBe ReviewTrustScorer.Trust.LOW
    }

    "どんな入力でも例外なし" {
        checkAll(Arb.float(0f..5f), Arb.int(0..100000)) { rating, count ->
            ReviewTrustScorer.evaluate(rating, count)
        }
    }

    "スコアは UNKNOWN 以外で 0-100 範囲内" {
        checkAll(Arb.float(0f..5f), Arb.int(1..100000)) { rating, count ->
            val r = ReviewTrustScorer.evaluate(rating, count)
            if (r.trust != ReviewTrustScorer.Trust.UNKNOWN) {
                r.score shouldBeInRange 0..100
            }
        }
    }
})
