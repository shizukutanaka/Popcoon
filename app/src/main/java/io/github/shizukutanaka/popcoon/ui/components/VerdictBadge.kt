package io.github.shizukutanaka.popcoon.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer

@Composable
fun VerdictBadge(verdict: BuyTimingScorer.Verdict, score: Int? = null) {
    val (labelRes, bg, fg) = when (verdict) {
        BuyTimingScorer.Verdict.BUY_NOW ->
            Triple(R.string.verdict_buy_now, Color(0xFF118A4E), Color.White)
        BuyTimingScorer.Verdict.NEUTRAL ->
            Triple(R.string.verdict_neutral, Color(0xFFB8860B), Color.White)
        BuyTimingScorer.Verdict.WAIT ->
            Triple(R.string.verdict_wait, Color(0xFFC0392B), Color.White)
    }
    val label = stringResource(labelRes)
    // TalkBack 用ラベルは可視ラベル (ロケール対応済み) を再利用し、スコア有無で文を補う。
    // 旧 verdictA11yLabel は verdict→語の対応を二重持ちし日本語固定だったため廃止。
    val a11yLabel = if (score != null)
        stringResource(R.string.a11y_verdict_score, label, score)
    else label
    Surface(
        color = bg,
        shape = RoundedCornerShape(CornerRadius.tag),
        modifier = Modifier.semantics { contentDescription = a11yLabel },
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            color = fg,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Preview @Composable fun VerdictBadgePreview() {
    VerdictBadge(BuyTimingScorer.Verdict.BUY_NOW, score = 85)
}
