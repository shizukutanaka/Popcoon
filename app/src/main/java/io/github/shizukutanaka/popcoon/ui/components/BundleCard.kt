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
import io.github.shizukutanaka.popcoon.feature.bundle.BundlePackDetector
import io.github.shizukutanaka.popcoon.ui.a11y.a11yHeading
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.PopcoonTheme
import io.github.shizukutanaka.popcoon.ui.theme.Spacing

/**
 * セット販売の実質単価カード。
 *
 * Popcoon 独自機能 (競合8社非搭載):
 *  「3本セット ¥2,400」を「1本あたり ¥800」に換算して提示。
 *
 * 現状の制約: 単品価格の照合元 (同一商品の単品リスティング検索) が未実装のため、
 * `BundlePackDetector.Analysis.singlePrice` は本番では常に null で、verdict は常に
 * UNKNOWN — つまり「単品より安いか」の判定・割引率表示は行われず、パック内単価
 * (例: 1本あたり ¥800) の換算表示のみが機能する。判定を有効化するには、呼び出し側
 * (ProductDetailViewModel) で単品版のタイトル検索マッチングを実装する必要がある。
 *
 * NOT_A_BUNDLE の場合は何も描画しない。UNKNOWN (単品価格不明) でも単価換算行は表示する。
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
