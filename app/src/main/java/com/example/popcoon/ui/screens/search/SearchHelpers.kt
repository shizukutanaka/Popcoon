package com.example.popcoon.ui.screens.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.data.model.Platform
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing

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
            p.displayName,
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
            heading = stringResource(R.string.search_hint)   // 「キーワードで商品を検索」
            body = "Amazon・楽天・Yahoo! の価格を\n一度に比較できます"
        }
        EmptyStatus.NO_RESULTS -> {
            icon = "🤷"
            heading = stringResource(R.string.search_empty)  // 「見つかりませんでした」
            body = "別のキーワードや JAN コードで\n再度お試しください"
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
 * エラー表示カード — 失敗時の最小限の情報だけ。
 */
@Composable
internal fun ErrorCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(CornerRadius.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            Modifier.padding(Spacing.ml),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
