package com.example.popcoon.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.example.popcoon.R
import com.example.popcoon.feature.review.ReviewTrustScorer
import com.example.popcoon.ui.theme.PopcoonTheme

/**
 * レビュー信頼度バッジ。
 *
 * ReviewTrustScorer の統計判定結果を表示する。
 * UNKNOWN (評価データなし) の場合は何も描画しない。
 *
 * サクラ疑い・サンプル不足は理由も併記し、ユーザーが
 * 「なぜ低信頼か」を理解できるようにする (説明可能性)。
 */
@Composable
fun ReviewTrustBadge(result: ReviewTrustScorer.Result) {
    if (result.trust == ReviewTrustScorer.Trust.UNKNOWN) return

    val trustLabel = when (result.trust) {
        ReviewTrustScorer.Trust.HIGH -> stringResource(R.string.confidence_high)
        ReviewTrustScorer.Trust.MEDIUM -> stringResource(R.string.confidence_medium)
        ReviewTrustScorer.Trust.LOW -> stringResource(R.string.confidence_low)
        ReviewTrustScorer.Trust.UNKNOWN -> stringResource(R.string.confidence_unknown)
    }
    val reasonText = when (result.reasonKey) {
        "review_trust_few_reviews" -> " (${stringResource(R.string.review_trust_few_reviews)})"
        "review_trust_too_perfect" -> " (${stringResource(R.string.review_trust_too_perfect)})"
        else -> ""
    }

    Text(
        text = "${stringResource(R.string.review_trust_label)}: $trustLabel$reasonText",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(name = "ReviewTrust – HIGH", showBackground = true)
@Composable
private fun ReviewTrustBadgeHighPreview() {
    PopcoonTheme {
        Surface {
            ReviewTrustBadge(
                ReviewTrustScorer.Result(ReviewTrustScorer.Trust.HIGH, 85, null),
            )
        }
    }
}

@Preview(name = "ReviewTrust – サクラ疑い", showBackground = true)
@Composable
private fun ReviewTrustBadgeSuspiciousPreview() {
    PopcoonTheme {
        Surface {
            ReviewTrustBadge(
                ReviewTrustScorer.Result(
                    ReviewTrustScorer.Trust.LOW, 35, "review_trust_too_perfect",
                ),
            )
        }
    }
}
