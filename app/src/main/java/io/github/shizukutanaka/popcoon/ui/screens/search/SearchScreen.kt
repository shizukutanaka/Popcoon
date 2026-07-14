package io.github.shizukutanaka.popcoon.ui.screens.search

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.shizukutanaka.popcoon.ui.UiText
import io.github.shizukutanaka.popcoon.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.Spacing
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.github.shizukutanaka.popcoon.ui.components.ProductCardSkeleton
import io.github.shizukutanaka.popcoon.ui.components.SaleBanner
import io.github.shizukutanaka.popcoon.ui.components.SearchSuggestions

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
    val showEcPrompt by viewModel.showEcPrompt.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(vmQuery) {
        if (vmQuery != query) query = vmQuery
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(Spacing.ml)) {
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
                // IME に「検索」ボタンを出し、押下でサジェストを閉じてフォーカスを外す
                // (= キーボードが下がり、結果が隠れない)。検索自体は debounce で実行済み。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    showSuggestions = false
                    focusManager.clearFocus()
                }),
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

        // ── EC 会員設定 案内バナー (実質価格ランキングの精度に直結、一度だけ表示) ──
        if (showEcPrompt) {
            io.github.shizukutanaka.popcoon.ui.components.EcMembershipBanner(
                onOpenSettings = {
                    viewModel.dismissEcPrompt()
                    onSettings()
                },
                onDismiss = viewModel::dismissEcPrompt,
            )
            Spacer(Modifier.height(Spacing.ml))
        }

        // ── セールバナー (タップでセールカレンダー画面へ) ──────────────────
        SaleBanner(modifier = Modifier.clickable(role = Role.Button) { onSaleCalendar() })
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
                    ErrorCard(message = s.message.asString(), onRetry = { viewModel.retry() })
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
                        val context = LocalContext.current
                        val shareChooserTitle = stringResource(R.string.action_share)
                        ResultsList(
                            items = displayed,
                            onClick = onProductClick,
                            onAddWatchlist = { product -> viewModel.addToWatchlist(product) },
                            onShare = { product ->
                                // 長押しメニューの共有 (機能過不足監査で発見: 以前は
                                // onAddWatchlist/onShare がどこからも供給されずメニュー項目が
                                // 常に無反応だった)。ProductDetailScreen の「購入ページを開く」
                                // ボタンとは異なりアフィリエイトタグは付与しない (SNS 等で共有される
                                // 生 URL に自動でタグを乗せるのは景品表示法上の開示義務との整合が
                                // 別途要検討のため、検索結果の簡易共有では素の URL に留める)。
                                if (product.url.isNotBlank()) {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "${product.title}\n${product.url}")
                                    }
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, shareChooserTitle),
                                    )
                                }
                            },
                        )
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
    onAddWatchlist: (Product) -> Unit = {},
    onShare: (Product) -> Unit = {},
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
                    onAddWatchlist = { onAddWatchlist(row.product) },
                    onShare = { onShare(row.product) },
                )
            }
        }
    }
}

// ── Data class ────────────────────────────────────────────────────────────────
data class SearchRow(
    val product: Product,
    val verdict: BuyTimingScorer.Verdict?,
    val warnings: List<UiText>,
    val score: Int = 0,
    /** 同一商品の他モール価格 (名寄せ結果、最安値順、自身を除く) */
    val alternatives: List<Product> = emptyList(),
    /**
     * PointSimulator が算出した実質価格 (EC ポイント還元後)。
     * 価格ソート・フィルタの単一の真実源。ViewModel が UserContext 込みで計算して注入する。
     * デフォルトは UserContext() (日付のみ自動、会員設定は後述の Settings から供給)。
     */
    val effectivePrice: Long = io.github.shizukutanaka.popcoon.feature.points.PointSimulator
        .simulate(product).effectivePrice,
)
