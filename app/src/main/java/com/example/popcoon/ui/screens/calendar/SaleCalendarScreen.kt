package com.example.popcoon.ui.screens.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.example.popcoon.R
import com.example.popcoon.data.model.Platform
import com.example.popcoon.feature.calendar.SaleCalendar
import com.example.popcoon.ui.a11y.a11yHeading
import com.example.popcoon.ui.localizedName
import com.example.popcoon.ui.theme.AppIcons
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.PopcoonTheme
import com.example.popcoon.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * セールカレンダー閲覧画面。
 *
 * 同種ソフト (Pricey 等) の主要差別化機能であるセールカレンダーを Popcoon でも提供する。
 * 検索画面の当日バナー ([com.example.popcoon.ui.components.SaleBanner]) は「今日」しか
 * 見せないが、本画面は今後の大型セール (プライムデー・楽天スーパーセール等) を一覧し、
 * ユーザーが「いつ買えば安いか」を事前に把握できるようにする。
 *
 * テスト済みの純ロジック [SaleCalendar] を再利用するだけなので ViewModel 不要。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleCalendarScreen(
    onBack: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val active = remember(today) { SaleCalendar.activeSales(today) }
    val upcoming = remember(today) { SaleCalendar.upcomingSales(today) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sale_calendar_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        if (active.isEmpty() && upcoming.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(Spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.sale_calendar_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = Spacing.ml),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            contentPadding = PaddingValues(vertical = Spacing.ml),
        ) {
            if (active.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.sale_calendar_active)) }
                items(active) { event -> SaleEventCard(event, today) }
            }
            if (upcoming.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.sale_calendar_upcoming)) }
                items(upcoming) { event -> SaleEventCard(event, today) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = Spacing.sm).a11yHeading(),
    )
}

@Composable
private fun SaleEventCard(event: SaleCalendar.Event, today: LocalDate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadius.card),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = "${platformLabel(event.platform)}${event.name}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tierColor(event.tier),
            )
            Text(
                text = stringResource(
                    R.string.sale_calendar_period,
                    event.startDate.format(DATE_FMT),
                    event.endDate.format(DATE_FMT),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            val daysUntil = ChronoUnit.DAYS.between(today, event.startDate)
            if (daysUntil > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(CornerRadius.pill),
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Text(
                        // plurals: 英語など単複を区別する言語で「in 1 day」/「in 2 days」を
                        // 正しく出し分ける (明日開始のセール = 1 日後は実際に到達する)。
                        text = pluralStringResource(
                            R.plurals.sale_calendar_days_until,
                            daysUntil.toInt(),
                            daysUntil.toInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.ml, vertical = Spacing.sm),
                    )
                }
            }
            if (event.description.isNotBlank()) {
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

// SaleBanner と同じ tier 別配色・プラットフォーム表記を踏襲する。
@Composable
private fun tierColor(tier: SaleCalendar.Tier): Color = when (tier) {
    SaleCalendar.Tier.MAJOR -> MaterialTheme.colorScheme.primary
    SaleCalendar.Tier.MEDIUM -> Color(0xFFB8860B)
    SaleCalendar.Tier.RECURRING -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun platformLabel(platform: Platform?): String =
    platform?.localizedName()?.let { "$it " } ?: ""

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

@Preview(name = "SaleCalendar – 6月初旬", showBackground = true)
@Composable
private fun SaleCalendarScreenPreview() {
    PopcoonTheme {
        SaleCalendarScreen(onBack = {}, today = LocalDate.of(2026, 6, 5))
    }
}
