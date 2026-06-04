package com.example.popcoon.feature.review

/**
 * レビュー信頼性スコアラー (統計ベース・オンデバイス)。
 *
 * 背景 (arXiv 2506.13313, UK DBT 2023):
 *  - third-party EC のレビューの 10件に1件以上が偽物で、多くがポジティブ。
 *  - AI 生成レビューは人間にもBERT等にも判別困難になりつつある。
 *  - テキストベース判別は LLM 必須でオンデバイス困難 + プライバシーリスク。
 *
 * Popcoon の方針 (I5/I6):
 *  - レビュー本文を端末外に送らない (プライバシー)。
 *  - レビュー数・平均評価の「統計的異常」のみで軽量に信頼度を推定。
 *  - 確定的・説明可能 (なぜ低信頼かをユーザーに提示できる)。
 *
 * 検出する異常パターン:
 *  1. レビュー数が極端に少ない (n < 5) のに満点 → サンプル不足
 *  2. レビュー数が異常に多く全て高評価 → サクラの可能性 (完璧すぎる)
 *  3. 評価なし → 信頼度算出不能
 *
 * 注: これは「偽レビュー断定」ではなく「鵜呑みにしない材料」の提供。
 *     誤検出を避けるため保守的に判定する (I7 = AI 生成物は誤り前提)。
 */
object ReviewTrustScorer {

    /** 信頼度レベル */
    enum class Trust { HIGH, MEDIUM, LOW, UNKNOWN }

    data class Result(
        val trust: Trust,
        /** 0-100 の信頼スコア (UNKNOWN は -1) */
        val score: Int,
        /** 低信頼の理由 (UI 表示用キー、null = 問題なし) */
        val reasonKey: String?,
    )

    private const val MIN_REVIEWS_FOR_TRUST = 5
    private const val SUSPICIOUS_HIGH_RATING = 4.9f
    private const val MANY_REVIEWS = 1000

    /**
     * @param rating 平均評価 (0.0-5.0、null = 評価なし)
     * @param reviewCount レビュー件数
     */
    fun evaluate(rating: Float?, reviewCount: Int): Result {
        // 評価なし → 判定不能
        if (rating == null || reviewCount <= 0) {
            return Result(Trust.UNKNOWN, -1, "review_trust_no_data")
        }

        // サンプル不足: レビューが少なすぎる
        if (reviewCount < MIN_REVIEWS_FOR_TRUST) {
            return Result(Trust.LOW, 30, "review_trust_few_reviews")
        }

        // 完璧すぎる: 大量レビューで平均がほぼ満点 → サクラ疑い
        // (正常な商品は不満レビューも一定数混ざり 4.9 未満に収束する)
        if (reviewCount >= MANY_REVIEWS && rating >= SUSPICIOUS_HIGH_RATING) {
            return Result(Trust.LOW, 35, "review_trust_too_perfect")
        }

        // 中量レビュー (5-99) → 中信頼
        if (reviewCount < 100) {
            return Result(Trust.MEDIUM, 60, null)
        }

        // 十分なレビュー数 + 自然な評価分布 → 高信頼
        return Result(Trust.HIGH, 85, null)
    }
}
