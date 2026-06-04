package com.example.popcoon.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import com.example.popcoon.feature.points.PointSimulator

/**
 * ポイント還元シミュレーターカード。
 *
 * 同種ソフト調査:
 *  - ほぼやすねっと: ポイントキャンペーン分を計算して実質価格を算出 (最大の差別化)
 *  - 最安値.com: クレジットカード/サービス選択 → 実質価格ランキング
 *  - Pricey: 送料も加味した価格比較
 *
 * 表示項目:
 *  - 表示価格 vs 実質価格の差額
 *  - ポイント内訳 (透明性 — 競合は内訳を見せない)
 *  - 今日有効なキャンペーンをハイライト
 */
@Composable
fun PointSimulatorCard(
    product: Product,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // 簡易 context (5のつく日 等の判定)
    val ctx = remember { PointSimulator.UserContext() }
    val result = remember(product) { PointSimulator.simulate(product, ctx) }

    if (result.pointsBack == 0L) return  // ポイントなしなら表示しない

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(Spacing.ml)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.point_effective_price),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                // 内訳展開ボタン
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.action_collapse)
                        } else {
                            stringResource(R.string.point_breakdown)
                        },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        com.example.popcoon.core.CurrencyFormatter.yen(result.effectivePrice),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(
                            R.string.point_effective_subtitle,
                            com.example.popcoon.core.CurrencyFormatter.yen(result.sticker),
                            com.example.popcoon.core.CurrencyFormatter.yen(result.pointsBack),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                // プラットフォームバッジ
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(CornerRadius.tag),
                ) {
                    Text(
                        platformLabel(product.platform),
                        Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            // 内訳 (透明性 — 競合にない機能)
            if (expanded && result.breakdown.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.ml))
                HorizontalDivider()
                Spacer(Modifier.height(Spacing.xs))
                result.breakdown.forEach { source ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.xxs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(source.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            com.example.popcoon.core.CurrencyFormatter.pointsBack(source.amount, source.rateString),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private fun platformLabel(p: Platform): String = when (p) {
    Platform.AMAZON -> "Amazon"
    Platform.RAKUTEN -> "楽天"
    Platform.YAHOO -> "Yahoo!"
}
