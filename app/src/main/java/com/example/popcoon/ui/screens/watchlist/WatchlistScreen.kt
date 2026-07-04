package com.example.popcoon.ui.screens.watchlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.data.db.WatchlistItem
import com.example.popcoon.feature.watchlist.WatchlistSort
import com.example.popcoon.feature.watchlist.WidgetVerdict
import com.example.popcoon.feature.scorer.BuyTimingScorer
import com.example.popcoon.ui.components.SmartCartCard
import com.example.popcoon.ui.components.SwipeToDelete
import com.example.popcoon.ui.components.TagDialog
import com.example.popcoon.ui.components.VerdictBadge
import com.example.popcoon.feature.notification.NotificationPermissionHelper
import com.example.popcoon.feature.notification.RequestNotificationPermission
import com.example.popcoon.ui.theme.AppIcons
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing
import com.example.popcoon.ui.util.HapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * ウォッチリスト画面。
 *
 * Apple HIG 適用:
 *  - スワイプ削除 (iOS 標準パターン)
 *  - Undo Snackbar (Forgiveness 原則: 取り消せる)
 *  - 空状態に CTA ボタン (次の行動を明確に)
 *  - 削除時に触覚フィードバック
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    onItemClick: (String) -> Unit,
    onBack: () -> Unit,
    onGoSearch: () -> Unit = {},
    viewModel: WatchlistViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    // StateFlow なので初期値は ViewModel 側が保持する (collectAsStateWithLifecycle は引数不要)。
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filteredItems by viewModel.filteredItems.collectAsStateWithLifecycle()
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val smartCart by viewModel.smartCart.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 目標価格ダイアログの対象アイテム（null = 非表示）
    var targetDialogItem by remember { mutableStateOf<WatchlistItem?>(null) }
    // タグ設定ダイアログの対象アイテム（null = 非表示）
    var tagDialogItem by remember { mutableStateOf<WatchlistItem?>(null) }

    // 初回ウォッチリスト追加時に通知権限を要求 (一度だけ)
    var requestNotificationPermission by remember { mutableStateOf(false) }
    var hasRequestedPermission by remember { mutableStateOf(false) }
    RequestNotificationPermission(shouldRequest = requestNotificationPermission) {
        requestNotificationPermission = false
        hasRequestedPermission = true
    }

    // items が 0→1 に変化した瞬間のみ要求
    var prevSize by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size) {
        if (items.size == 1 && prevSize == 0 &&
            !hasRequestedPermission &&
            !NotificationPermissionHelper.isGranted(context)
        ) {
            requestNotificationPermission = true
        }
        prevSize = items.size
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.watchlist_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.nav_back)) }
                },
                actions = {
                    // 2件以上ある時のみ並べ替えメニューを表示
                    if (items.size >= 2) {
                        WatchlistSortMenu(
                            current = sortMode,
                            onSelect = viewModel::setSortMode,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (items.isEmpty()) {
            WatchlistEmptyState(
                modifier = Modifier.padding(padding),
                onGoSearch = onGoSearch,
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).padding(Spacing.ml),
                verticalArrangement = Arrangement.spacedBy(Spacing.ml),
            ) {
                // タグフィルタチップ（タグが1つ以上使われている場合のみ表示）。
                // (機能過不足監査 B4: ウォッチリストのタグ/フォルダ分類が無かった、への対応)
                if (availableTags.isNotEmpty()) {
                    item(key = "tag_filter", contentType = "tag_filter") {
                        TagFilterRow(
                            availableTags = availableTags,
                            selectedTag = selectedTag,
                            onSelect = viewModel::selectTagFilter,
                        )
                    }
                }
                // スマートカート最適化カード（2件以上ある場合のみ）
                // contentType でカードと行を区別し、スクロール時の composition 再利用を効かせる。
                smartCart?.let { result ->
                    item(key = "smart_cart", contentType = "smart_cart") {
                        SmartCartCard(cartResult = result)
                    }
                }
                items(
                    filteredItems,
                    key = { it.productKey },
                    contentType = { "watchlist_row" },
                ) { item ->
                    SwipeToDelete(
                        onDelete = {
                            HapticFeedback.heavy(context)
                            viewModel.remove(item.productKey)

                            // Undo Snackbar (Apple Forgiveness 原則)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = undoRemovedMessage(context, item.title),
                                    actionLabel = context.getString(R.string.watchlist_undo_action),
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.add(item)
                                    HapticFeedback.success(context)
                                }
                            }
                        },
                    ) {
                        WatchlistRow(
                            item = item,
                            onClick = {
                                HapticFeedback.light(context)
                                onItemClick(item.productKey)
                            },
                            onSetTarget = { targetDialogItem = item },
                            onSetTag = { tagDialogItem = item },
                            onToggleStockAlert = {
                                viewModel.setStockAlertEnabled(
                                    item.productKey,
                                    !item.stockAlertEnabled,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    // 目標価格ダイアログ
    targetDialogItem?.let { item ->
        com.example.popcoon.ui.components.TargetPriceDialog(
            currentTarget = item.targetPrice,
            onConfirm = { target ->
                viewModel.setTargetPrice(item.productKey, target)
                targetDialogItem = null
            },
            onDismiss = { targetDialogItem = null },
        )
    }

    // タグ (フォルダ分類) ダイアログ
    tagDialogItem?.let { item ->
        TagDialog(
            currentTag = item.tag,
            existingTags = availableTags,
            onConfirm = { tag ->
                viewModel.setTag(item.productKey, tag)
                tagDialogItem = null
            },
            onDismiss = { tagDialogItem = null },
        )
    }
}

private fun undoRemovedMessage(context: android.content.Context, title: String): String =
    context.getString(R.string.watchlist_undo_removed, title.take(15))

/**
 * ウォッチリスト行の買い時バッジ用 Verdict を返す。
 *
 * 判定はホーム画面ウィジェットと同じ [WidgetVerdict]（テスト済み純関数・履歴/通信不要）を
 * 再利用し、「ウィジェットは買い時を出すのにアプリ内ウォッチリストは出さない」不整合を解消する。
 * NEUTRAL は視覚ノイズになるため null（バッジ非表示）を返し、BUY_NOW / WAIT のみ表示する。
 */
internal fun watchlistBuyVerdict(item: WatchlistItem): BuyTimingScorer.Verdict? =
    when (WidgetVerdict.forItem(item.realPrice, item.targetPrice, item.addedPrice)) {
        WidgetVerdict.BUY_NOW -> BuyTimingScorer.Verdict.BUY_NOW
        WidgetVerdict.WAIT -> BuyTimingScorer.Verdict.WAIT
        else -> null
    }

/** WatchlistSort.Mode → 表示用文字列リソース。 */
@Composable
private fun sortModeLabel(mode: WatchlistSort.Mode): String = stringResource(
    when (mode) {
        WatchlistSort.Mode.ADDED_DESC -> R.string.wl_sort_added
        WatchlistSort.Mode.PRICE_ASC -> R.string.wl_sort_price_asc
        WatchlistSort.Mode.PRICE_DESC -> R.string.wl_sort_price_desc
        WatchlistSort.Mode.DISCOUNT_DESC -> R.string.wl_sort_discount
        WatchlistSort.Mode.NAME_ASC -> R.string.wl_sort_name
        WatchlistSort.Mode.TARGET_PROGRESS -> R.string.wl_sort_target
    },
)

/**
 * 並べ替えメニュー（TopAppBar アクション）。
 * アイコンをタップするとドロップダウンで全モードを表示し、選択中にはチェックを付ける。
 */
@Composable
private fun WatchlistSortMenu(
    current: WatchlistSort.Mode,
    onSelect: (WatchlistSort.Mode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                AppIcons.Sort,
                contentDescription = stringResource(R.string.watchlist_sort_label),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            WatchlistSort.Mode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(sortModeLabel(mode)) },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                    trailingIcon = {
                        if (mode == current) {
                            Icon(AppIcons.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun WatchlistRow(
    item: WatchlistItem,
    onClick: () -> Unit,
    onSetTarget: () -> Unit,
    onSetTag: () -> Unit,
    onToggleStockAlert: () -> Unit,
) {
    Surface(
        // カード本体を 1 フォーカスに merge。入れ子のチップ (目標価格 / 在庫アラート) は
        // 自前のクリックアクションを持つため merge 境界となり、別フォーカスのまま残る。
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onClick() }
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(CornerRadius.card),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(Spacing.ml),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        com.example.popcoon.core.CurrencyFormatter.yen(item.realPrice),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (item.listPrice > item.realPrice) {
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            com.example.popcoon.core.CurrencyFormatter.yen(item.listPrice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 買い時バッジ: ウィジェットと同じ WidgetVerdict を再利用し、
                    // 行動に最も近いウォッチリスト画面でも「今が買いか」を示す。
                    // NEUTRAL はノイズなので非表示 (BUY_NOW / WAIT のみ表示)。
                    watchlistBuyVerdict(item)?.let { verdict ->
                        Spacer(Modifier.width(Spacing.md))
                        VerdictBadge(verdict)
                    }
                }
                // 追加時からの変動（横ばい時は非表示）
                SinceAddedDelta(item = item)
                // 目標価格バッジ / タグ / 在庫アラートチップ
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TargetPriceChip(item = item, onClick = onSetTarget)
                    TagChip(item = item, onClick = onSetTag)
                    StockAlertChip(item = item, onClick = onToggleStockAlert)
                }
            }
            // ← にスワイプで削除ヒント
            Text(
                "←",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

/**
 * ウォッチ追加時からの価格変動を小さく表示する。
 * 値下がりは強調色、値上がりはエラー色。横ばい・基準なしは何も描画しない。
 */
@Composable
private fun SinceAddedDelta(item: WatchlistItem) {
    val delta = com.example.popcoon.feature.watchlist.WatchlistPriceDelta
        .since(item.addedPrice, item.realPrice) ?: return
    if (delta.direction == com.example.popcoon.feature.watchlist.WatchlistPriceDelta.Direction.FLAT) return

    val down = delta.direction == com.example.popcoon.feature.watchlist.WatchlistPriceDelta.Direction.DOWN
    val amountText = com.example.popcoon.core.CurrencyFormatter.yen(delta.absAmount)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(
            if (down) R.string.since_added_down else R.string.since_added_up,
            amountText,
            delta.absPercent,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = if (down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

/**
 * 在庫アラートの on/off チップ。
 * タップで切り替え。ON 時はプライマリカラーで強調表示。
 */
@Composable
private fun StockAlertChip(item: WatchlistItem, onClick: () -> Unit) {
    FilterChip(
        selected = item.stockAlertEnabled,
        onClick = onClick,
        label = {
            Text(
                stringResource(
                    if (item.stockAlertEnabled) R.string.stock_alert_on else R.string.stock_alert_off,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        },
    )
}

/**
 * 目標価格の状態を表示する小さなチップ。
 *  - 未設定: 「目標価格」設定を促す控えめなボタン
 *  - 設定済み: 「目標 ¥X」を表示
 *  - 達成済み (現在価格 ≤ 目標): 「目標達成」を強調色で表示
 */
@Composable
private fun TargetPriceChip(item: WatchlistItem, onClick: () -> Unit) {
    val target = item.targetPrice
    val reached = target != null && item.realPrice <= target
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                when {
                    reached -> stringResource(R.string.target_price_reached)
                    target != null -> stringResource(
                        R.string.target_price_set,
                        com.example.popcoon.core.CurrencyFormatter.yen(target),
                    )
                    else -> stringResource(R.string.target_price_button)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        },
        colors = if (reached) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
    )
}

/**
 * タグ (フォルダ分類) の状態を表示する小さなチップ。
 *  - 未分類: 「タグ」設定を促す控えめなボタン
 *  - 設定済み: タグ名を表示
 */
@Composable
private fun TagChip(item: WatchlistItem, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                item.tag ?: stringResource(R.string.watchlist_tag_button),
                style = MaterialTheme.typography.labelSmall,
            )
        },
    )
}

/**
 * タグフィルタチップ行。「すべて」+ 使用中の各タグ。
 * 単一選択 (FilterChip の selected で現在のフィルタを示す)。
 */
@Composable
private fun TagFilterRow(
    availableTags: List<String>,
    selectedTag: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "tag_filter_all") {
            FilterChip(
                selected = selectedTag == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.watchlist_tag_filter_all)) },
            )
        }
        items(availableTags, key = { it }) { tag ->
            FilterChip(
                selected = selectedTag == tag,
                onClick = { onSelect(if (selectedTag == tag) null else tag) },
                label = { Text(tag) },
            )
        }
    }
}

/**
 * Apple HIG 空状態パターン:
 *  1. 大きなアイコン/イラスト
 *  2. 見出し (何がないか)
 *  3. ボディ (どうすればいいか)
 *  4. CTA ボタン (次の行動)
 */
@Composable
private fun WatchlistEmptyState(
    modifier: Modifier = Modifier,
    onGoSearch: () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
            modifier = Modifier.padding(Spacing.xxxl),
        ) {
            Text("⭐", style = MaterialTheme.typography.displayLarge)
            Text(
                stringResource(R.string.watchlist_empty),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.watchlist_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onGoSearch) {
                Text(stringResource(R.string.nav_search))
            }
        }
    }
}
