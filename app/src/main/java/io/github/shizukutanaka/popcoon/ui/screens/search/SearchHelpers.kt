package io.github.shizukutanaka.popcoon.ui.screens.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.ui.localizedName
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.Spacing

/**
 * プラットフォーム表示チップ (Amazon / 楽天 / Yahoo の色付きラベル)。
 */
@Composable
internal fun PlatformChip(p: Platform) {
    Surface(
        shape = RoundedCornerShape(CornerRadius.tag),
        color = Color(p.brandColor),
    ) {
        Text(
            p.localizedName(),
            Modifier.padding(horizontal = Spacing.ml, vertical = Spacing.sm),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

/** EmptyState の種類 — 文字列比較を排除し型安全に */
internal enum class EmptyStatus { IDLE, NO_RESULTS, GENERIC }

/**
 * Apple HIG 空状態 4 要素パターン。
 *  1. アイコン (絵文字)
 *  2. 見出し (太字 / 大)
 *  3. 説明 (小 / 薄色)
 *  4. (任意) CTA ボタン
 */
@Composable
internal fun EmptyState(status: EmptyStatus, customText: String = "") {
    val icon: String
    val heading: String
    val body: String
    when (status) {
        EmptyStatus.IDLE -> {
            icon = "🔍"
            heading = stringResource(R.string.search_hint)
            body = stringResource(R.string.search_idle_body)
        }
        EmptyStatus.NO_RESULTS -> {
            icon = "🤷"
            heading = stringResource(R.string.search_empty)
            body = stringResource(R.string.search_no_results_body)
        }
        EmptyStatus.GENERIC -> {
            icon = "📋"
            heading = customText
            body = ""
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
            modifier = Modifier.padding(Spacing.xxxl),
        ) {
            Text(icon, style = MaterialTheme.typography.displayMedium)
            Text(
                heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (body.isNotEmpty()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * エラー表示カード — メッセージ + (任意) リトライボタン。
 *
 * 検索の失敗は一過性のネットワーク障害が多く、同一クエリは debounce で再発火しない。
 * onRetry を渡すと「再試行」ボタンを表示し、ユーザーがクエリを変えずにやり直せる。
 */
@Composable
internal fun ErrorCard(message: String, onRetry: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(CornerRadius.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.ml)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}
