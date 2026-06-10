package com.example.popcoon.ui.screens.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.popcoon.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.popcoon.R
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing
import com.example.popcoon.data.model.Product
import com.example.popcoon.feature.scorer.BuyTimingScorer
import com.example.popcoon.ui.components.ProductCardSkeleton
import com.example.popcoon.ui.components.SaleBanner
import com.example.popcoon.ui.components.SearchSuggestions

/**
 * 検索画面 — Popcoon のメイン画面。
 *
 * 構成:
 *  - 検索バー (バーコードボタン + ウォッチリストボタン)
 *  - オートコンプリート (Trie + 履歴)
 *  - セールバナー (今日のセール情報)
 *  - AnimatedContent で状態遷移 (Idle / Loading / Results / Empty / Error)
 *  - スケルトンスクリーン (Apple HIG — スピナー代替)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onProductClick: (Product) -> Unit,
    onSettings: () -> Unit = {},
    onWatchlist: () -> Unit = {},
    onBarcode: () -> Unit = {},
    onSaleCalendar: () -> Unit = {},
    viewModel: SearchViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val vmQuery by viewModel.currentQuery.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    LaunchedEffect(vmQuery) {
        if (vmQuery != query) query = vmQuery
    }

    Column(modifier = Modifier.fillMaxSize().padding(Spacing.ml)) {
        // ── ツールバー行 ───────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.currentQuery.value = it
                    viewModel.onQueryChange(it)
                    showSuggestions = true
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = {
                    IconButton(onClick = onBarcode) {
                        Icon(AppIcons.Barcode, contentDescription = stringResource(R.string.barcode_scan))
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(Spacing.ml))
            IconButton(onClick = onSaleCalendar) {
                Icon(AppIcons.Calendar, contentDescription = stringResource(R.string.sale_calendar_open))
            }
            IconButton(onClick = onWatchlist) {
                Icon(AppIcons.Save, contentDescription = stringResource(R.string.nav_watchlist))
            }
        }

        // ── オートコンプリート ─────────────────────────────────────────────
        if (showSuggestions) {
            SearchSuggestions(
                suggestions = suggestions,
                recentSearches = recentSearches,
                query = query,
                onSelect = { selected ->
                    query = selected
                    viewModel.onQueryChange(selected)
                    showSuggestions = false
                },
            )
        }
        Spacer(Modifier.height(Spacing.ml))

        // ── セールバナー (タップでセールカレンダー画面へ) ──────────────────
        SaleBanner(modifier = Modifier.clickable { onSaleCalendar() })
        Spacer(Modifier.height(Spacing.ml))

        // ── 状態遷移 (Apple HIG: AnimatedContent でフェード) ──────────────
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            },
            label = "searchStateTransition",
        ) { s ->
            when (s) {
                SearchUiState.Idle ->
                    EmptyState(EmptyStatus.IDLE)
                SearchUiState.Loading ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.ml)) {
                        repeat(5) { ProductCardSkeleton() }
                    }
                is SearchUiState.Error ->
                    ErrorCard(message = s.message)
                SearchUiState.Empty ->
                    EmptyState(EmptyStatus.NO_RESULTS)
                is SearchUiState.Results -> {
                    var sortOption by remember { mutableStateOf(SortOption.BUY_TIMING) }
                    var filter by remember { mutableStateOf(SearchFilter()) }
                    val displayed = remember(s.items, sortOption, filter) {
                        SortOption.apply(filter.apply(s.items), sortOption)
                    }
                    Column {
                        // ソートチップ
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            contentPadding = PaddingValues(horizontal = Spacing.sm),
                        ) {
                            lazyItems(SortOption.entries) { opt ->
                                FilterChip(
                                    selected = sortOption == opt,
                                    onClick = { sortOption = opt },
                                    label = { Text(stringResource(opt.labelRes)) },
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        ResultsList(items = displayed, onClick = onProductClick)
                    }
                }
            }
        }
    }
}

// ── ResultsList ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsList(
    items: List<SearchRow>,
    onClick: (Product) -> Unit,
    onRefresh: () -> Unit = {},
) {
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = onRefresh,
        state = refreshState,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
        ) {
            lazyItems(items, key = { it.product.key }) { row ->
                ProductRow(
                    row = row,
                    onClick = { onClick(row.product) },
                )
            }
        }
    }
}

// ── Data class ────────────────────────────────────────────────────────────────
data class SearchRow(
    val product: Product,
    val verdict: BuyTimingScorer.Verdict?,
    val warnings: List<String>,
    val score: Int = 0,
    /** 同一商品の他モール価格 (名寄せ結果、最安値順、自身を除く) */
    val alternatives: List<Product> = emptyList(),
)
