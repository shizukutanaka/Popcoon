package com.example.popcoon.ui.components

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.popcoon.feature.scorer.BuyTimingScorer
import com.example.popcoon.ui.theme.PopcoonTheme

/**
 * 主要 Composable の @Preview 集約。
 *
 * Apple デザインプロセス:
 *  - Xcode Preview = コードとデザインを並行して確認
 *  - Android の @Preview はその直接対応物
 *  - ダーク / ライト / フォントサイズ大 を必ずカバー
 *
 * PreviewParameterProvider を使わず読みやすい個別 @Preview で定義する。
 */

// ── ScoreCard ────────────────────────────────────────────────────────────────

@Preview(name = "ScoreCard – BuyNow (Light)", showBackground = true)
@Composable
private fun ScoreCardBuyNowPreview() {
    PopcoonTheme {
        Surface {
            ScoreCard(
                score = 82,
                verdict = BuyTimingScorer.Verdict.BUY_NOW,
                confidence = "高",
                signals = listOf(
                    BuyTimingScorer.Signal("過去最安近辺", 30),
                    BuyTimingScorer.Signal("下降トレンド", 20),
                    BuyTimingScorer.Signal("割引率10%以上", 15),
                ),
            )
        }
    }
}

@Preview(name = "ScoreCard – Wait (Dark)", showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScoreCardWaitDarkPreview() {
    PopcoonTheme(darkTheme = true) {
        Surface {
            ScoreCard(
                score = 28,
                verdict = BuyTimingScorer.Verdict.WAIT,
                confidence = "中",
                signals = listOf(
                    BuyTimingScorer.Signal("上昇トレンド", -20),
                    BuyTimingScorer.Signal("高ボラティリティ", -10),
                ),
            )
        }
    }
}

@Preview(name = "ScoreCard – Neutral (Expanded)", showBackground = true)
@Composable
private fun ScoreCardNeutralPreview() {
    PopcoonTheme {
        Surface {
            ScoreCard(
                score = 50,
                verdict = BuyTimingScorer.Verdict.NEUTRAL,
                confidence = "低",
                signals = emptyList(),
            )
        }
    }
}

// ── ShimmerEffect ────────────────────────────────────────────────────────────

@Preview(name = "ProductCardSkeleton", showBackground = true)
@Composable
private fun ProductCardSkeletonPreview() {
    PopcoonTheme {
        Surface {
            Column {
                repeat(3) { ProductCardSkeleton() }
            }
        }
    }
}

@Preview(name = "ProductDetailSkeleton", showBackground = true)
@Composable
private fun ProductDetailSkeletonPreview() {
    PopcoonTheme {
        Surface { ProductDetailSkeleton() }
    }
}

// ── OfflineBanner ─────────────────────────────────────────────────────────────

@Preview(name = "SaleBanner – Active", showBackground = true)
@Composable
private fun SaleBannerPreview() {
    PopcoonTheme {
        Surface { SaleBanner() }
    }
}
