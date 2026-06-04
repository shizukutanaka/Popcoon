package com.example.popcoon.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.popcoon.data.model.PriceRecord
import java.time.Instant

/**
 * 価格チャート — 同種ソフト調査結果に基づく実装。
 *
 * 競合機能:
 *  - Keepa: 詳細チャート (但し UI は embedded webpage 的)
 *  - Pricey: 価格チャート機能で買い時一目瞭然
 *  - CamelCamelCamel: シンプルなチャート
 *
 * Popcoon 方針:
 *  - Compose Canvas で純粋に描画 (依存ライブラリゼロ)
 *  - 過去最安値ライン + 現在価格マーカーを強調 (買い時判断支援)
 *  - 小画面でも読みやすいよう余白多め (Keepa の弱点を解決)
 */
@Composable
fun PriceChart(
    records: List<PriceRecord>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    minMarkerColor: Color = Color(0xFF118A4E),
) {
    if (records.size < 2) {
        Surface(
            modifier = modifier.fillMaxWidth().height(Spacing.chart),
            shape = RoundedCornerShape(CornerRadius.card),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(Modifier.padding(Spacing.ml)) {
                Text(
                    "価格履歴データ不足",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val sorted = records.sortedBy { it.recordedAt }
    val prices = sorted.map { it.realPrice }
    val minPrice = prices.min()
    val maxPrice = prices.max()
    val range = (maxPrice - minPrice).coerceAtLeast(1L)

    Surface(
        modifier = modifier.fillMaxWidth().height(Spacing.chart),
        shape = RoundedCornerShape(CornerRadius.card),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().padding(Spacing.ml)) {
            val w = size.width
            val h = size.height
            if (sorted.size < 2 || w <= 0f || h <= 0f) return@Canvas

            // 1. 最安値の水平線 (破線)
            val minY = h - ((minPrice - minPrice).toFloat() / range * h)
            drawLine(
                color = minMarkerColor.copy(alpha = 0.35f),
                start = Offset(0f, minY),
                end = Offset(w, minY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )

            // 2. ライン
            val path = Path()
            sorted.forEachIndexed { i, record ->
                val x = w * i.toFloat() / (sorted.size - 1)
                val y = h - ((record.realPrice - minPrice).toFloat() / range * h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 4f),
            )

            // 3. 現在価格 (最新 = 最右) のマーカー
            val lastIdx = sorted.size - 1
            val lastX = w
            val lastY = h - ((sorted.last().realPrice - minPrice).toFloat() / range * h)
            drawCircle(
                color = lineColor,
                radius = 8f,
                center = Offset(lastX, lastY),
            )
            drawCircle(
                color = Color.White,
                radius = 4f,
                center = Offset(lastX, lastY),
            )

            // 4. 最安値到達ポイント
            sorted.forEachIndexed { i, record ->
                if (record.realPrice == minPrice) {
                    val x = w * i.toFloat() / (sorted.size - 1)
                    val y = h
                    drawCircle(
                        color = minMarkerColor,
                        radius = 6f,
                        center = Offset(x, y - 4f),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewPriceChart() {
    val records = (0..29).map { i ->
        PriceRecord(
            productKey = "p", platform = "amazon",
            listPrice = 5000, realPrice = (3500 + (i * 73) % 1500).toLong(),
            recordedAt = Instant.now().minusSeconds((30 - i) * 86400L),
        )
    }
    androidx.compose.material3.MaterialTheme {
        PriceChart(records)
    }
}
