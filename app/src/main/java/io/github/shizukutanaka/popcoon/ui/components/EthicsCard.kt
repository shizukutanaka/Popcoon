package io.github.shizukutanaka.popcoon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.feature.ethics.EcoEthicsScorer
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.PopcoonTheme
import io.github.shizukutanaka.popcoon.ui.theme.Spacing

/**
 * 環境・倫理スコアカード (EcoEthicsScorer の可視化)。
 *
 * Popcoon 独自機能 (競合非搭載): 原産国の CO2 係数・労働権利指標と
 * 製品カテゴリの基準排出量から、購入前に「環境・倫理コスト」を提示する。
 *
 * 原産国が判明している商品でのみ表示する (不明時は意味のあるスコアにならないため
 * 呼び出し側で null を渡してカードを描画しない)。
 */
@Composable
fun EthicsCard(
    score: EcoEthicsScorer.Score,
    origin: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadius.card),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.ethics_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${score.overall}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor(score.overall),
                )
            }
            Text(
                text = stringResource(R.string.ethics_overall),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            EthicsBar(stringResource(R.string.ethics_co2), score.co2Score)
            EthicsBar(stringResource(R.string.ethics_labor), score.laborScore)

            Text(
                text = stringResource(
                    R.string.ethics_co2_estimate,
                    formatKg(score.co2Kg),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            if (!origin.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.ethics_origin, origin),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (score.greenAlternative != null) {
                Text(
                    text = stringResource(R.string.ethics_green_alt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            // overall はサプライチェーン透明性(20%)・循環経済性(15%)を含む合計35%ぶんが
            // 商品ごとの実データではなく業界平均の暫定値 (EcoEthicsScorer.
            // SUPPLY_CHAIN_SCORE_DEFAULT / CIRCULAR_ECONOMY_SCORE_DEFAULT) で構成される。
            // co2Score/laborScore の内訳バーだけを見せて総合スコアが完全に商品固有であるかの
            // ように誤解させていた (機能過不足監査で発見)。
            Text(
                text = stringResource(R.string.ethics_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

@Composable
private fun EthicsBar(label: String, value: Int) {
    Column(Modifier.padding(top = Spacing.md)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("$value / 100", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { value.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            color = scoreColor(value),
        )
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 70 -> Color(0xFF2E7D32) // green 800 — 良好
    score >= 45 -> Color(0xFFF9A825) // amber 800 — 普通
    else -> Color(0xFFC62828)        // red 800 — 懸念
}

/** CO2 kg を小数1桁で表示 (ロケール非依存の素朴フォーマット)。 */
private fun formatKg(kg: Double): String {
    val rounded = kotlin.math.round(kg * 10) / 10
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

@Preview(name = "EthicsCard – 中国産スマホ", showBackground = true)
@Composable
private fun EthicsCardPreview() {
    PopcoonTheme {
        EthicsCard(
            score = EcoEthicsScorer.score(
                country = "CN",
                category = "smartphone",
                certifications = emptyList(),
            ),
            origin = "CN",
        )
    }
}
