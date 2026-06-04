package com.example.popcoon.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.feature.scorer.BuyTimingScorer

@Composable
fun VerdictBadge(verdict: BuyTimingScorer.Verdict) {
    val (label, bg, fg) = when (verdict) {
        BuyTimingScorer.Verdict.BUY_NOW ->
            Triple("買い時", Color(0xFF118A4E), Color.White)
        BuyTimingScorer.Verdict.NEUTRAL ->
            Triple("様子見", Color(0xFFB8860B), Color.White)
        BuyTimingScorer.Verdict.WAIT ->
            Triple("待ち", Color(0xFFC0392B), Color.White)
    }
    Surface(color = bg, shape = RoundedCornerShape(CornerRadius.tag)) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            color = fg,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Preview @Composable fun VerdictBadgePreview() {
    VerdictBadge(BuyTimingScorer.Verdict.BUY_NOW)
}
