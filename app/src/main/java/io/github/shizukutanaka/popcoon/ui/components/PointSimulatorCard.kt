package io.github.shizukutanaka.popcoon.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.Spacing
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.feature.points.PointSimulator
import io.github.shizukutanaka.popcoon.ui.localizedName
import io.github.shizukutanaka.popcoon.ui.nameRes
import io.github.shizukutanaka.popcoon.ui.util.HapticFeedback

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
    userCtx: PointSimulator.UserContext = remember { PointSimulator.UserContext() },
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val result = remember(product, userCtx) { PointSimulator.simulate(product, userCtx) }

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
                TextButton(onClick = {
                    HapticFeedback.light(context)
                    expanded = !expanded
                }) {
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
                        io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(result.effectivePrice),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(
                            R.string.point_effective_subtitle,
                            io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(result.sticker),
                            io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(result.pointsBack),
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
                        product.platform.localizedName(),
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
                        // source.name は日本語固定文字列 (PointSimulatorTest.kt が
                        // .name.contains(...) で厳密比較する内部識別子) なので UI 表示には
                        // 使わない。nameRes() で kind からロケール対応の文字列リソースへ変換する。
                        Text(stringResource(source.nameRes()), style = MaterialTheme.typography.bodySmall)
                        Text(
                            io.github.shizukutanaka.popcoon.core.CurrencyFormatter.pointsBack(source.amount, source.rateString),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
