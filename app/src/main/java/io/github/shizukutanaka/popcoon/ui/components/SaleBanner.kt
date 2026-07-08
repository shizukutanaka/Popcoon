package io.github.shizukutanaka.popcoon.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.Spacing
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.feature.calendar.SaleCalendar
import io.github.shizukutanaka.popcoon.ui.localizedName
import io.github.shizukutanaka.popcoon.ui.theme.PopcoonTheme
import java.time.LocalDate

/**
 * 今日のセール情報バナー。
 *
 * 同種ソフト調査:
 *  - プライシー: セールカレンダー機能で各 EC のセール期間を事前確認
 *  - Yahoo! ショッピング: 「5のつく日」情報をウィジェットやトップに常時表示
 *  → 「毎日のようにアプリを開いては今日は何が得なのかなぁと見ている」ユーザー行動
 *
 * Popcoon 方針:
 *  - 今日アクティブなセールを水平スクロールで表示 (邪魔にならない)
 *  - セールなし = バナー非表示 (画面を圧迫しない)
 *  - MAJOR セールは特別色で強調
 */
@Composable
fun SaleBanner(
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val activeSales = remember(today) {
        SaleCalendar.activeSales(today)
    }

    if (activeSales.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        activeSales.take(5).forEach { event ->
            SaleChip(event = event)
        }
    }
}

@Composable
private fun SaleChip(event: SaleCalendar.Event) {
    val (bg, fg) = when (event.tier) {
        SaleCalendar.Tier.MAJOR -> Pair(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
        )
        SaleCalendar.Tier.MEDIUM -> Pair(
            Color(0xFFB8860B),
            Color.White,
        )
        SaleCalendar.Tier.RECURRING -> Pair(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }

    // ?.let のラムダは Composable スコープでないため localizedName() を先に呼ぶ
    val platformLabel = event.platform?.localizedName()?.let { "$it " } ?: ""

    Surface(
        color = bg,
        shape = RoundedCornerShape(CornerRadius.pill),
    ) {
        Text(
            text = "$platformLabel${event.name}",
            modifier = Modifier.padding(horizontal = Spacing.ml, vertical = Spacing.sm),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = if (event.tier == SaleCalendar.Tier.MAJOR) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Preview(name = "SaleBanner – 楽天スーパーセール開催中", showBackground = true)
@Composable
private fun SaleBannerPreview() {
    PopcoonTheme {
        SaleBanner(today = LocalDate.of(2026, 6, 5))
    }
}
