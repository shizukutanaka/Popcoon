package io.github.shizukutanaka.popcoon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 *  長期コストが膨らむ製品の「N年総コスト」を可視化する。
 *
 *  例: 1万円のインクジェットプリンターが、インク代で5年20万円に。
 *  これを購入前に提示し、ダークパターン的な「本体だけ安い」釣りを暴く。
 *
 * 保有年数を選べる (既定5年): TCOCalculator.calculate() の残存価値率は
 * スマートフォン/ノートPC/プリンター全カテゴリとも年数5でちょうど (または既に) 0 に
 * なるよう設計されており、years を常に5固定で呼んでいた旧実装では残存価値が
 * 恒久的に非表示だった (機能過不足監査で発見)。2〜3年で見れば中古市場価値が
 * 現実的に残るスマホ/ノートPCで、この機能が実際に意味を持つようにする。
 */
@Composable
fun TCOCard(
    purchasePrice: Long,
    category: String,
    modifier: Modifier = Modifier,
) {
    var years by rememberSaveable { mutableIntStateOf(5) }
    val result = remember(purchasePrice, category, years) {
        TCOCalculator.calculate(purchasePrice = purchasePrice, category = category, years = years)
    }

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

            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                YEAR_OPTIONS.forEach { y ->
                    FilterChip(
                        selected = years == y,
                        onClick = { years = y },
                        label = { Text(stringResource(R.string.tco_years_chip, y)) },
                    )
                }
            }

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

            // 代替製品との比較 (現状インクジェット→インクタンク式のみ)。
            // 割高になるケース (高額プリンターで「3台分の本体価格」前提が崩れる) では
            // 有用な助言にならないため savings > 0 のときのみ表示する。
            result.vsAlternative?.takeIf { it.savings > 0 }?.let { alt ->
                val altLabel = when (alt.kind) {
                    TCOCalculator.AlternativeKind.INK_TANK_PRINTER ->
                        stringResource(R.string.tco_alt_ink_tank_label)
                }
                Text(
                    text = stringResource(
                        R.string.tco_alt_savings,
                        altLabel,
                        years,
                        CurrencyFormatter.yen(alt.savings),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
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

// 保有年数の選択肢。1〜3年は現実的な中古市場価値が残るスマホ/ノートPCで意味を持ち、
// 5・7年は「使い倒す」長期保有シナリオ (残存価値がほぼ/完全に 0 になる想定)。
private val YEAR_OPTIONS = listOf(1, 2, 3, 5, 7)

@Preview(name = "TCOCard – インクジェット", showBackground = true)
@Composable
private fun TCOCardPreview() {
    PopcoonTheme {
        TCOCard(purchasePrice = 10_000, category = "inkjet_printer")
    }
}

@Preview(name = "TCOCard – スマートフォン", showBackground = true)
@Composable
private fun TCOCardSmartphonePreview() {
    PopcoonTheme {
        TCOCard(purchasePrice = 120_000, category = "smartphone")
    }
}
