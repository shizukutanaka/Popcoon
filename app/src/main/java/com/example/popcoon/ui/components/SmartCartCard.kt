package com.example.popcoon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.example.popcoon.R
import com.example.popcoon.core.CurrencyFormatter
import com.example.popcoon.data.model.Platform
import com.example.popcoon.feature.cart.CrossMallCartOptimizer
import com.example.popcoon.feature.cart.SmartCartService
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.PopcoonTheme
import com.example.popcoon.ui.theme.Spacing

/**
 * 横断スマートカート最適化カード（UNIVERSAL_CART_SPEC.md / PORTING_SPEC.md #4）。
 *
 * ウォッチリスト内商品を最適に振り分けた場合の「合計 + モール別内訳」を表示。
 * 送料無料ライン到達による節約をユーザーに提示し、バラバラに購入する非効率を解消する。
 */
@Composable
fun SmartCartCard(
    cartResult: SmartCartService.SmartCartResult,
    modifier: Modifier = Modifier,
) {
    val result = cartResult.optimized
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.smart_cart_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (result.greedy) {
                    Text(
                        text = stringResource(R.string.smart_cart_greedy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
            }

            // モール別振り分けサマリ
            val mallSummary = result.assignment.values
                .groupBy { it }
                .entries
                .sortedBy { it.key }
                .joinToString(" · ") { (mall, indices) ->
                    "${Platform.fromIdOrNull(mall)?.displayName ?: mall} ${indices.size}点"
                }
            Text(
                text = mallSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = Spacing.xs),
            )

            // 合計金額
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.smart_cart_total, CurrencyFormatter.yen(result.total.toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                // 節約額（現状プランとの差）
                if (cartResult.savingVsNaive > 0) {
                    Text(
                        text = stringResource(
                            R.string.smart_cart_saving,
                            CurrencyFormatter.yen(cartResult.savingVsNaive.toLong()),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 送料・クーポン内訳（非ゼロ時のみ）
            if (result.shippingTotal > 0 || result.couponTotal > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (result.shippingTotal > 0) {
                        Text(
                            text = stringResource(
                                R.string.smart_cart_shipping,
                                CurrencyFormatter.yen(result.shippingTotal.toLong()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    if (result.couponTotal > 0) {
                        Text(
                            text = stringResource(
                                R.string.smart_cart_coupon,
                                CurrencyFormatter.yen(result.couponTotal.toLong()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "SmartCartCard", showBackground = true)
@Composable
private fun SmartCartCardPreview() {
    PopcoonTheme {
        SmartCartCard(
            cartResult = SmartCartService.SmartCartResult(
                cartItems = emptyList(),
                optimized = CrossMallCartOptimizer.Result(
                    assignment = mapOf(0 to "amazon", 1 to "amazon", 2 to "rakuten"),
                    total = 8500.0,
                    perMallSubtotal = mapOf("amazon" to 6800.0, "rakuten" to 1700.0),
                    shippingTotal = 0.0,
                    couponTotal = 200.0,
                    numMalls = 2,
                ),
                naiveTotal = 9200.0,
                savingVsNaive = 700.0,
            ),
        )
    }
}
