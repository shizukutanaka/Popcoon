package io.github.shizukutanaka.popcoon.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.R
import androidx.compose.ui.tooling.preview.Preview
import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.github.shizukutanaka.popcoon.ui.components.ProductImage
import io.github.shizukutanaka.popcoon.ui.theme.AppIcons
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.PopcoonTheme
import io.github.shizukutanaka.popcoon.ui.theme.Spacing
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.shizukutanaka.popcoon.ui.a11y.a11yDescription
import io.github.shizukutanaka.popcoon.ui.a11y.priceA11yLabel
import io.github.shizukutanaka.popcoon.ui.components.VerdictBadge

/**
 * 検索結果1行の Product カード。
 *
 * Apple HIG 適用:
 *  - 商品画像 + 価格 + Verdict バッジを縦構成 (情報優先順位)
 *  - 長押しでコンテキストメニュー (Apple Quick Actions 相当)
 *  - 警告チップは最大2件 (情報過多防止)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProductRow(
    row: SearchRow,
    onClick: () -> Unit,
    onAddWatchlist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true },
            )
            // TalkBack はカード全体を 1 つのフォーカス対象として読み上げる。
            // merge しないと画像・チップ・バッジ・価格・タイトル・警告で個別に止まり、
            // 1 タップ対象なのに何度もスワイプが必要になる (アクセシビリティ HIG 違反)。
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(CornerRadius.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(Spacing.ml)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductImage(
                    imageUrl = row.product.imageUrl,
                    size = 52.dp,
                )
                Spacer(Modifier.width(Spacing.ml))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformChip(row.product.platform)
                        Spacer(Modifier.width(Spacing.sm))
                        row.verdict?.let { VerdictBadge(it, score = row.score) }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    val priceDesc = priceA11yLabel(row.product.totalPrice)
                    Text(
                        text = io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(row.product.totalPrice),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = priceDesc },
                    )
                    // 実質価格がステッカー価格と異なる場合 (ポイント還元あり) に副行表示。
                    // ソートが effectivePrice で行われていることを視覚的に示す。
                    if (row.effectivePrice < row.product.totalPrice) {
                        Text(
                            text = stringResource(R.string.points_effective) +
                                " " + io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(row.effectivePrice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                row.product.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // 名寄せ: 他モールに同一商品がある場合、最安値であることを示す
            if (row.alternatives.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                val maxAlt = row.alternatives.maxOf { it.totalPrice }
                val saving = maxAlt - row.product.totalPrice
                Surface(
                    shape = RoundedCornerShape(CornerRadius.tag),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = if (saving > 0) {
                            stringResource(
                                R.string.product_cross_mall_cheapest,
                                row.alternatives.size + 1,
                                io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(saving),
                            )
                        } else {
                            stringResource(R.string.product_cross_mall_compare, row.alternatives.size + 1)
                        },
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (row.warnings.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                Row {
                    row.warnings.take(2).forEach { w ->
                        val text = w.asString()
                        Surface(
                            shape = RoundedCornerShape(CornerRadius.tag),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .padding(end = Spacing.sm)
                                .a11yDescription("⚠ $text"),
                        ) {
                            Text(
                                text, Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.watchlist_add)) },
                onClick = { showMenu = false; onAddWatchlist?.invoke() },
                leadingIcon = { Icon(AppIcons.Save, stringResource(R.string.watchlist_add)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_received)) },
                onClick = { showMenu = false; onShare?.invoke() },
                leadingIcon = { Icon(AppIcons.Share, stringResource(R.string.share_received)) },
            )
        }
    }
}

@Preview(name = "ProductRow – BUY_NOW", showBackground = true)
@androidx.compose.runtime.Composable
private fun ProductRowPreview() {
    PopcoonTheme {
        ProductRow(
            row = SearchRow(
                product = Product(
                    sku = "B0TEST001",
                    title = "Sony WH-1000XM5 ワイヤレスノイズキャンセリングヘッドホン",
                    platform = Platform.AMAZON,
                    listPrice = 44_000,
                    realPrice = 29_800,
                ),
                verdict = BuyTimingScorer.Verdict.BUY_NOW,
                warnings = listOf(io.github.shizukutanaka.popcoon.ui.UiText.DynamicString("常態割引")),
                score = 85,
                alternatives = emptyList(),
            ),
            onClick = {},
        )
    }
}
