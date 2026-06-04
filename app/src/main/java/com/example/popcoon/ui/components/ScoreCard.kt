package com.example.popcoon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.feature.scorer.BuyTimingScorer

/**
 * 買い時スコアカード — 段階的開示。
 *
 * Apple HIG より:
 *  - 最初は要約のみ表示 (Progressive Disclosure)
 *  - 詳細は「タップして展開」で Depth を表現
 *  - 過剰な情報で認知負荷をかけない (Deference)
 *  - 視覚的スコアリング (数値だけでなく視覚で伝える)
 */
@Composable
fun ScoreCard(
    score: Int,
    verdict: BuyTimingScorer.Verdict?,
    confidence: String?,
    signals: List<BuyTimingScorer.Signal>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(CornerRadius.modal),
        colors = CardDefaults.cardColors(
            containerColor = when (verdict) {
                BuyTimingScorer.Verdict.BUY_NOW -> Color(0xFF118A4E).copy(alpha = 0.12f)
                BuyTimingScorer.Verdict.WAIT -> Color(0xFFC0392B).copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            // ── 要約行 (常時表示) ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // スコアを円形ゲージで表示 (Apple のリング UI 風)
                ScoreRing(score = score, verdict = verdict)

                Spacer(Modifier.width(Spacing.lg))

                Column(Modifier.weight(1f)) {
                    verdict?.let { v ->
                        val label = when (v) {
                            BuyTimingScorer.Verdict.BUY_NOW -> stringResource(R.string.score_buy_now)
                            BuyTimingScorer.Verdict.NEUTRAL -> stringResource(R.string.score_neutral)
                            BuyTimingScorer.Verdict.WAIT -> stringResource(R.string.score_wait)
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    confidence?.let {
                        Text(
                            stringResource(R.string.score_confidence, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 展開インジケーター
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 詳細 (タップで展開) ───────────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.ml))
                    HorizontalDivider()
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        stringResource(R.string.score_breakdown),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.xs))

                    signals
                        .filter { it.contribution != 0 && it.name.isNotEmpty() }
                        .forEach { sig ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = Spacing.xxs),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    sig.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${if (sig.contribution > 0) "+" else ""}${sig.contribution}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (sig.contribution > 0)
                                        Color(0xFF118A4E) else Color(0xFFC0392B),
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun ScoreRing(score: Int, verdict: BuyTimingScorer.Verdict?) {
    val color = when (verdict) {
        BuyTimingScorer.Verdict.BUY_NOW -> Color(0xFF118A4E)
        BuyTimingScorer.Verdict.WAIT -> Color(0xFFC0392B)
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // 背景円
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = color.copy(alpha = 0.15f))
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = score / 100f * 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f),
            )
        }
        Text(
            "$score",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}
