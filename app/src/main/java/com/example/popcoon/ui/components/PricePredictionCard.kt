package com.example.popcoon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.example.popcoon.R
import com.example.popcoon.core.CurrencyFormatter
import com.example.popcoon.feature.prediction.PricePredictionEngine
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.PopcoonTheme
import com.example.popcoon.ui.theme.Spacing

/**
 * 価格予測カード。
 *
 * Holt's linear smoothing による 7日後・30日後の予測価格を、
 * 予測区間 (±margin) 付きで表示する。
 *
 * 競合 (Keepa 等) は過去履歴のみ表示し「未来予測」は提供しない。
 * Popcoon は予測 + 信頼度で「待つべきか今買うべきか」の判断材料を与える。
 *
 * 信頼度が LOW の場合は予測の不確実性を明示し、誤誘導を避ける。
 */
@Composable
fun PricePredictionCard(
    prediction: PricePredictionEngine.Prediction,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadius.card),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.prediction_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.prediction_confidence,
                    confidenceLabel(prediction.confidence),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 買い時度 (0-100%): 過去価格に対する現在価格の安さ + 下落トレンド。
            // エンジンが算出済みだが従来 UI 非表示だった指標を明示する。
            BuyNowProbabilityRow(probability = prediction.buyNowProbability)

            PredictionRow(
                label = stringResource(R.string.prediction_7d),
                price = prediction.predicted7d,
                margin = prediction.predictionMargin,
            )
            PredictionRow(
                label = stringResource(R.string.prediction_30d),
                price = prediction.predicted30d,
                margin = prediction.predictionMargin,
            )
            // A1: 季節分解予測（週次パターンがある商品で精度向上、PORTING_SPEC.md A1）
            if (prediction.seasonalForecast7d > 0L) {
                PredictionRow(
                    label = stringResource(R.string.prediction_seasonal_7d),
                    price = prediction.seasonalForecast7d,
                    margin = 0L,
                )
            }

            // 過去レンジも併記
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        R.string.prediction_historic_low,
                        CurrencyFormatter.yen(prediction.historicLow),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.prediction_historic_high,
                        CurrencyFormatter.yen(prediction.historicHigh),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 買い時度バー。probability(0.0-1.0)を 0-100% で表示し、値が高いほど強調色。
 * 高 (≥0.7) = primary、中 (≥0.4) = onSurface、低 = onSurfaceVariant。
 */
@Composable
private fun BuyNowProbabilityRow(probability: Float) {
    val pct = (probability * 100).toInt().coerceIn(0, 100)
    val color = when {
        probability >= 0.7f -> MaterialTheme.colorScheme.primary
        probability >= 0.4f -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val a11y = stringResource(R.string.prediction_buy_now_prob_a11y, pct)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.prediction_buy_now_prob),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.prediction_buy_now_prob_value, pct),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
private fun PredictionRow(label: String, price: Long, margin: Long) {
    val visualText = if (margin > 0) {
        "${CurrencyFormatter.yen(price)} ± ${CurrencyFormatter.yen(margin)}"
    } else {
        CurrencyFormatter.yen(price)
    }
    // スクリーンリーダー向け: 記号でなく語で読み上げ
    val a11yText = if (margin > 0) {
        stringResource(
            R.string.prediction_margin_a11y,
            "$label ${CurrencyFormatter.yenAccessible(price)}",
            CurrencyFormatter.yenAccessible(margin),
        )
    } else {
        "$label ${CurrencyFormatter.yenAccessible(price)}"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = a11yText },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = visualText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun confidenceLabel(c: PricePredictionEngine.Confidence): String = stringResource(
    when (c) {
        PricePredictionEngine.Confidence.HIGH -> R.string.confidence_high
        PricePredictionEngine.Confidence.MEDIUM -> R.string.confidence_medium
        PricePredictionEngine.Confidence.LOW -> R.string.confidence_low
        PricePredictionEngine.Confidence.UNKNOWN -> R.string.confidence_unknown
    },
)

@Preview(name = "PricePredictionCard", showBackground = true)
@Composable
private fun PricePredictionCardPreview() {
    PopcoonTheme {
        PricePredictionCard(
            prediction = PricePredictionEngine.Prediction(
                currentPrice = 12800,
                predicted7d = 12500,
                predicted30d = 11900,
                buyNowProbability = 0.6f,
                historicLow = 11500,
                historicHigh = 14800,
                confidence = PricePredictionEngine.Confidence.MEDIUM,
                predictionMargin = 350,
            ),
        )
    }
}
