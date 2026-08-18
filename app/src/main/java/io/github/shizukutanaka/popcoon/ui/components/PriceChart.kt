package io.github.shizukutanaka.popcoon.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.Spacing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.github.shizukutanaka.popcoon.ui.a11y.priceA11yLabel
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
 *  - 期間切替 (1週間/1ヶ月/全期間) — 競合標準機能。全 records は保持したまま
 *    表示範囲だけをフィルタするので、切替のたびに再取得は不要。
 */
enum class PriceChartRange(val days: Int?) {
    WEEK(7), MONTH(30), ALL(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceChart(
    records: List<PriceRecord>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    minMarkerColor: Color = Color(0xFF118A4E),
) {
    var selectedRange by remember { mutableStateOf(PriceChartRange.ALL) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(bottom = Spacing.sm),
        ) {
            PriceChartRange.entries.forEach { r ->
                FilterChip(
                    selected = selectedRange == r,
                    onClick = { selectedRange = r },
                    label = {
                        Text(
                            when (r) {
                                PriceChartRange.WEEK -> stringResource(R.string.price_chart_range_week)
                                PriceChartRange.MONTH -> stringResource(R.string.price_chart_range_month)
                                PriceChartRange.ALL -> stringResource(R.string.price_chart_range_all)
                            },
                        )
                    },
                )
            }
        }
        // filterByRange 内の Instant.now() をコンポジション実行パスで直接呼ぶと、
        // 再コンポジションのたびにカットオフ時刻が (ミリ秒単位で) ずれ、records の中身が
        // 変わっていなくても新しいフィルタ結果インスタンスが生成され、下流の
        // PriceChartCanvas の remember(records) が無駄に再計算される (商用リリース監査で発見)。
        // records/selectedRange が変わらない限り再計算しないよう remember でホイストする。
        val filteredRecords = remember(records, selectedRange) { filterByRange(records, selectedRange) }
        PriceChartCanvas(
            records = filteredRecords,
            lineColor = lineColor,
            minMarkerColor = minMarkerColor,
        )
    }
}

/** 選択期間で records を絞り込む (pure function、テスト容易)。ALL は絞り込まない。 */
internal fun filterByRange(records: List<PriceRecord>, chartRange: PriceChartRange): List<PriceRecord> {
    val days = chartRange.days ?: return records
    val cutoff = Instant.now().minusSeconds(days.toLong() * 86_400)
    return records.filter { it.recordedAt >= cutoff }
}

/**
 * 描画対象のレコードを時系列順で返す (pure function、テスト容易)。
 *
 * `realPrice <= 0` は取得失敗を 0 円として記録した汚染レコードであり、実際に成立した
 * 価格ではない。混ぜるとグラフの下端が常に ¥0 に張り付いて実際の変動幅が潰れ、
 * a11y の読み上げも「期間最安 0円」になり、先頭/末尾が ¥0 だと傾向 (上昇/下降) の
 * 判定まで反転する。`WidgetVerdict` / `WatchlistPriceDelta` は既に同じ規則で
 * 0 以下を除外しており、グラフだけが例外になっていた。
 */
internal fun plottableRecords(records: List<PriceRecord>): List<PriceRecord> =
    records.filter { it.realPrice > 0 }.sortedBy { it.recordedAt }

@Composable
private fun PriceChartCanvas(
    records: List<PriceRecord>,
    lineColor: Color,
    minMarkerColor: Color,
) {
    // remember はコンポジション構造を安定させるため早期 return より前に置く。
    val sorted = remember(records) { plottableRecords(records) }
    if (sorted.size < 2) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(Spacing.chart),
            shape = RoundedCornerShape(CornerRadius.card),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(Modifier.padding(Spacing.ml)) {
                Text(
                    stringResource(R.string.price_chart_insufficient),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val minPrice = remember(sorted) { sorted.minOf { it.realPrice } }
    val maxPrice = remember(sorted) { sorted.maxOf { it.realPrice } }
    val range = remember(minPrice, maxPrice) { (maxPrice - minPrice).coerceAtLeast(1L) }

    // Canvas は純粋な描画で自動的な読み上げ内容を一切持たないため、TalkBack には
    // 何も伝わらなかった (機能過不足監査で発見)。現在価格・期間最安値・傾向の
    // 3点に要約した contentDescription を Canvas 自体に付与する。
    val trendRes = when {
        sorted.first().realPrice < sorted.last().realPrice -> R.string.price_chart_trend_up
        sorted.first().realPrice > sorted.last().realPrice -> R.string.price_chart_trend_down
        else -> R.string.price_chart_trend_flat
    }
    val chartA11y = stringResource(
        R.string.price_chart_a11y,
        stringResource(trendRes),
        priceA11yLabel(sorted.last().realPrice),
        priceA11yLabel(minPrice),
    )

    Surface(
        modifier = Modifier.fillMaxWidth().height(Spacing.chart),
        shape = RoundedCornerShape(CornerRadius.card),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.ml)
                .semantics { contentDescription = chartA11y },
        ) {
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
