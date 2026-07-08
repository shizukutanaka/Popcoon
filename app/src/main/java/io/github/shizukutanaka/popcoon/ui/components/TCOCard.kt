package io.github.shizukutanaka.popcoon.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.core.CurrencyFormatter
import io.github.shizukutanaka.popcoon.feature.tco.TCOCalculator
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.PopcoonTheme
import io.github.shizukutanaka.popcoon.ui.theme.Spacing

/**
 * TCO (総保有コスト) カード。
 *
 * Popcoon 独自機能 (競合非搭載):
 *  プリンター・PC・冷蔵庫など、本体価格は安くても消耗品・電力で
 *  長期コストが膨らむ製品の「5年総コスト」を可視化する。
 *
 *  例: 1万円のインクジェットプリンターが、インク代で5年20万円に。
 *  これを購入前に提示し、ダークパターン的な「本体だけ安い」釣りを暴く。
 */
@Composable
fun TCOCard(
    result: TCOCalculator.Result,
    years: Int = 5,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadius.card),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.tco_title, years),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            TcoRow(stringResource(R.string.tco_purchase), result.purchasePrice)
            if (result.consumablesTotal > 0) {
                TcoRow(stringResource(R.string.tco_consumables), result.consumablesTotal)
            }
            if (result.energyTotal > 0) {
                TcoRow(stringResource(R.string.tco_energy), result.energyTotal)
            }
            if (result.residualValue > 0) {
                TcoRow(stringResource(R.string.tco_residual), -result.residualValue)
            }

            // 合計を強調
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.tco_total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    CurrencyFormatter.yen(result.totalTco),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(
                    R.string.tco_per_month,
                    CurrencyFormatter.yen(result.tcoPerMonth),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TcoRow(label: String, amount: Long) {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(CurrencyFormatter.yen(amount), style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(name = "TCOCard – インクジェット", showBackground = true)
@Composable
private fun TCOCardPreview() {
    PopcoonTheme {
        TCOCard(
            result = TCOCalculator.Result(
                purchasePrice = 10000,
                consumablesTotal = 180000,
                energyTotal = 1200,
                maintenance = 0,
                residualValue = 0,
                totalTco = 191200,
                tcoPerMonth = 3186,
            ),
        )
    }
}
