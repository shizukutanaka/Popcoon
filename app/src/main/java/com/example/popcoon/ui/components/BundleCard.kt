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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.example.popcoon.R
import com.example.popcoon.core.CurrencyFormatter
import com.example.popcoon.feature.bundle.BundlePackDetector
import com.example.popcoon.ui.a11y.a11yHeading
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.PopcoonTheme
import com.example.popcoon.ui.theme.Spacing

/**
 * セット販売の実質単価カード。
 *
 * Popcoon 独自機能 (競合8社非搭載):
 *  「3本セット ¥2,400」を「1本あたり ¥800」に換算して提示。
 *  まとめ買いが本当にお得か一目で分かる。
 *
 * NOT_A_BUNDLE / UNKNOWN の場合は何も描画しない。
 */
@Composable
fun BundleCard(
    analysis: BundlePackDetector.Analysis,
    modifier: Modifier = Modifier,
) {
    // 単品でない、かつ実質単価が計算できる場合のみ表示
    if (analysis.verdict == BundlePackDetector.Verdict.NOT_A_BUNDLE) return
    if (analysis.packCount <= 1) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadius.card),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.bundle_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.a11yHeading(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.bundle_pack_count, analysis.packCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.bundle_unit_price,
                        CurrencyFormatter.yen(analysis.unitPriceInBundle),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            // 単品比較がある場合は節約額も表示
            analysis.savingsPercent?.let { pct ->
                Text(
                    text = if (pct >= 0) {
                        stringResource(R.string.bundle_savings_discount, pct.toInt())
                    } else {
                        stringResource(R.string.bundle_savings_markup, (-pct).toInt())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pct >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Preview(name = "BundleCard – 5個セット", showBackground = true)
@Composable
private fun BundleCardPreview() {
    PopcoonTheme {
        BundleCard(
            analysis = BundlePackDetector.Analysis(
                bundlePrice = 2400,
                packCount = 3,
                singlePrice = null,
                unitPriceInBundle = 800,
                savingsPerUnit = null,
                savingsPercent = null,
                verdict = BundlePackDetector.Verdict.UNKNOWN,
            ),
        )
    }
}
