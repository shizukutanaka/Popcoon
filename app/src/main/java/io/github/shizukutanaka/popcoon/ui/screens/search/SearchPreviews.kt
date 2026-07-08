package io.github.shizukutanaka.popcoon.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.github.shizukutanaka.popcoon.ui.theme.PopcoonTheme
import io.github.shizukutanaka.popcoon.ui.theme.Spacing

/**
 * SearchScreen 関連コンポーネントの @Preview。
 *
 * Apple 設計プロセス:
 *  - デザイナーとエンジニアがプレビューで即座に確認
 *  - Light / Dark / Japanese / Long text の各状態をカバー
 */

@Preview(name = "SearchHelpers – EmptyState Idle", showBackground = true)
@Composable
private fun EmptyStateIdlePreview() {
    PopcoonTheme { Surface { EmptyState(EmptyStatus.IDLE) } }
}

@Preview(name = "SearchHelpers – EmptyState No Results", showBackground = true)
@Composable
private fun EmptyStateEmptyPreview() {
    PopcoonTheme { Surface { EmptyState(EmptyStatus.NO_RESULTS) } }
}

@Preview(name = "PlatformChip – All Platforms", showBackground = true)
@Composable
private fun PlatformChipAllPreview() {
    PopcoonTheme {
        Surface {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PlatformChip(Platform.AMAZON)
                PlatformChip(Platform.RAKUTEN)
                PlatformChip(Platform.YAHOO)
            }
        }
    }
}

@Preview(name = "ProductRow – Buy Now", showBackground = true)
@Composable
private fun ProductRowBuyNowPreview() {
    PopcoonTheme {
        Surface {
            ProductRow(
                row = SearchRow(
                    product = Product(
                        sku = "B0EXAMPLE01",
                        title = "ハーゲンダッツ アイスクリーム ストロベリー 110mL ×6個",
                        platform = Platform.AMAZON,
                        realPrice = 1_280,
                        listPrice = 1_800,
                        shippingFee = 0,
                    ),
                    verdict = BuyTimingScorer.Verdict.BUY_NOW,
                    warnings = listOf("常設セールの疑い"),
                    score = 82,
                ),
                onClick = {},
            )
        }
    }
}

@Preview(name = "ProductRow – Dark Mode", showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProductRowDarkPreview() {
    PopcoonTheme(darkTheme = true) {
        Surface {
            ProductRow(
                row = SearchRow(
                    product = Product(
                        sku = "B0EXAMPLE02",
                        title = "プリンター インクジェット",
                        platform = Platform.RAKUTEN,
                        realPrice = 28_000,
                        listPrice = 35_000,
                    ),
                    verdict = BuyTimingScorer.Verdict.NEUTRAL,
                    warnings = emptyList(),
                    score = 50,
                ),
                onClick = {},
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "SortOption Chips", showBackground = true)
@Composable
private fun SortChipsPreview() {
    PopcoonTheme {
        Surface {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SortOption.entries.forEach { opt ->
                    FilterChip(
                        selected = opt == SortOption.BUY_TIMING,
                        onClick = {},
                        label = { Text(opt.name) },
                    )
                }
            }
        }
    }
}
