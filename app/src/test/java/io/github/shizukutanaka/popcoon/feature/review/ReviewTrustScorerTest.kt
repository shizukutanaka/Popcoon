package io.github.shizukutanaka.popcoon.feature.review

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

    "1000件+4.9評価は LOW (MANY_REVIEWS 境界の識別)" {
        ReviewTrustScorer.evaluate(4.9f, 1000).trust shouldBe ReviewTrustScorer.Trust.LOW
    }

    // 回帰: 旧実装は MANY_REVIEWS=1000 の単一しきい値しか持たず、reviewCount=999 が
    // どんなに満点に近くても無条件で素通りしていた (2026-07 リサーチで発見: このファイルの
    // 旧テスト「999件+高評価は HIGH」は、実はこの抜け穴自体を固定していただけだった)。
    // 中量域 (300〜999件) では通常域より厳しい 4.95 基準で「完璧すぎる」を検出する。
    "300〜999件でも評価 4.95 以上ならサクラ疑い LOW (中量域の抜け穴修正)" {
        ReviewTrustScorer.evaluate(4.95f, 999).trust shouldBe ReviewTrustScorer.Trust.LOW
        ReviewTrustScorer.evaluate(5.0f, 300).trust shouldBe ReviewTrustScorer.Trust.LOW
    }

    "中量域でも評価が 4.95 未満なら自然な分布として HIGH のまま" {
        ReviewTrustScorer.evaluate(4.94f, 999).trust shouldBe ReviewTrustScorer.Trust.HIGH
    }

    "300件未満の中量域は「完璧すぎる」判定の対象外" {
        // 件数が少なすぎて統計的異常とまでは言えないため、4.95 以上でも HIGH のまま
        // (別途 MIN_REVIEWS_FOR_TRUST や 5-99 の MEDIUM ルールは適用される)
        ReviewTrustScorer.evaluate(5.0f, 299).trust shouldBe ReviewTrustScorer.Trust.HIGH
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
