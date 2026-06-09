package com.example.popcoon.ui.screens.watchlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.data.db.WatchlistItem
import com.example.popcoon.feature.watchlist.WatchlistSort
import com.example.popcoon.ui.components.SmartCartCard
import com.example.popcoon.ui.components.SwipeToDelete
import com.example.popcoon.feature.notification.NotificationPermissionHelper
import com.example.popcoon.feature.notification.RequestNotificationPermission
import com.example.popcoon.ui.theme.AppIcons
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing
import com.example.popcoon.ui.util.HapticFeedback
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
    val items by viewModel.items.collectAsState(initial = emptyList())
    val smartCart by viewModel.smartCart.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState(initial = WatchlistSort.Mode.ADDED_DESC)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 目標価格ダイアログの対象アイテム（null = 非表示）
    var targetDialogItem by remember { mutableStateOf<WatchlistItem?>(null) }

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
                // スマートカート最適化カード（2件以上ある場合のみ）
                smartCart?.let { result ->
                    item(key = "smart_cart") {
                        SmartCartCard(cartResult = result)
                    }
                }
                items(items, key = { it.productKey }) { item ->
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
}

private fun undoRemovedMessage(context: android.content.Context, title: String): String =
    context.getString(R.string.watchlist_undo_removed, title.take(15))

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
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
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
                }
                // 目標価格バッジ / 設定ボタン
                Spacer(Modifier.height(6.dp))
                TargetPriceChip(item = item, onClick = onSetTarget)
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
                "商品を検索して詳細画面の ★ を押すと\nここに価格変動を追跡できます",
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
