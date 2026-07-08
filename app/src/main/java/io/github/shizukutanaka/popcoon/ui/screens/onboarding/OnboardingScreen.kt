package io.github.shizukutanaka.popcoon.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.ui.a11y.a11yDecorative
import io.github.shizukutanaka.popcoon.ui.theme.Spacing
import io.github.shizukutanaka.popcoon.ui.util.HapticFeedback
import kotlinx.coroutines.launch

/**
 * 3 ステップのオンボーディング。
 *
 * 業界統計: D7 retention 20%未満 = 商品問題。
 * オンボーディングで「価値を体験」させることで初日離脱を抑制する。
 *
 * 設計:
 *  - 各画面 1 ベネフィット (情報過多を避ける)
 *  - 視線の流れ: アイコン → 見出し → 説明 → CTA
 *  - スキップ可能 (強制しない)
 *  - 完了状態は SharedPreferences で永続化
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val pages = listOf(
        OnboardingPage(
            icon = "🔍",
            title = stringResource(R.string.onboarding_title1),
            description = stringResource(R.string.onboarding_desc1),
            cta = stringResource(R.string.onboarding_next),
        ),
        OnboardingPage(
            icon = "🚨",
            title = stringResource(R.string.onboarding_title2),
            description = stringResource(R.string.onboarding_desc2),
            cta = stringResource(R.string.onboarding_next),
        ),
        OnboardingPage(
            icon = "🎯",
            title = stringResource(R.string.onboarding_title3),
            description = stringResource(R.string.onboarding_desc3),
            cta = stringResource(R.string.onboarding_start),
        ),
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize()) {
        // edge-to-edge (targetSdk 35): 背景は全画面に敷き、コンテンツは safeDrawing で
        // ステータスバー / ナビゲーションバーを避ける。スキップボタンが時計と重ならない。
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // スキップボタン (右上)
            Box(Modifier.fillMaxWidth().padding(Spacing.ml), contentAlignment = Alignment.TopEnd) {
                TextButton(onClick = onComplete) {
                    Text(stringResource(R.string.onboarding_skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { pageIndex ->
                OnboardingPageContent(pages[pageIndex])
            }

            // ドット
            Row(
                Modifier.fillMaxWidth().padding(Spacing.ml),
                horizontalArrangement = Arrangement.Center,
            ) {
                pages.indices.forEach { i ->
                    val color = if (i == pagerState.currentPage)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline
                    Box(
                        Modifier
                            .padding(horizontal = 10.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }

            // CTA
            Button(
                onClick = {
                    HapticFeedback.light(context)
                    if (pagerState.currentPage < pages.lastIndex) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onComplete()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(Spacing.ml),
            ) {
                Text(pages.getOrNull(pagerState.currentPage)?.cta ?: "")
            }
        }
    }
}

private data class OnboardingPage(
    val icon: String,
    val title: String,
    val description: String,
    val cta: String,
)

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.ml),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(page.icon, fontSize = 80.sp, modifier = Modifier.a11yDecorative())
        Spacer(Modifier.height(Spacing.ml))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.ml))
        Text(
            page.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

