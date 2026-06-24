package com.example.popcoon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.popcoon.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.ui.a11y.a11yMinTouchTarget
import com.example.popcoon.ui.theme.Spacing
import com.example.popcoon.ui.theme.IconSize

/**
 * 検索サジェスト。
 *
 * Apple の UISearchController と同等の体験:
 *  - タイプ中に候補をリアルタイム表示
 *  - 検索履歴 (時計アイコン) と Trie 候補 (虫眼鏡アイコン) を区別
 *  - 候補タップで即検索
 *  - クエリが空なら最近の検索履歴を表示
 *
 * Apple HIG:
 *  - 複合ジェスチャー: 各操作に明確な視覚的フィードバック
 *  - サジェストは軽量でスクロールを邪魔しない
 */
@Composable
fun SearchSuggestions(
    suggestions: List<String>,
    recentSearches: List<String>,
    query: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = if (query.isBlank()) {
        recentSearches.take(5).map { SuggestionItem(it, isHistory = true) }
    } else {
        suggestions.take(6).map { SuggestionItem(it, isHistory = false) }
    }

    AnimatedVisibility(
        visible = items.isNotEmpty(),
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            tonalElevation = 4.dp,
            shadowElevation = 2.dp,
        ) {
            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                if (query.isBlank() && recentSearches.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.search_recent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        )
                    }
                }
                items(items, key = { "${it.isHistory}:${it.text}" }) { item ->
                    SuggestionRow(item = item, onSelect = onSelect)
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

private data class SuggestionItem(val text: String, val isHistory: Boolean)

@Composable
private fun SuggestionRow(item: SuggestionItem, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .a11yMinTouchTarget()
            .clickable(role = Role.Button) { onSelect(item.text) }
            .padding(horizontal = Spacing.lg, vertical = Spacing.ml),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (item.isHistory) AppIcons.History else AppIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.md),
        )
        Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
