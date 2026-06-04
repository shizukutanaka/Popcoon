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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                items(items, key = { it.productKey }) { item ->
                    SwipeToDelete(
                        onDelete = {
                            HapticFeedback.heavy(context)
                            viewModel.remove(item.productKey)

                            // Undo Snackbar (Apple Forgiveness 原則)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "「${item.title.take(15)}」を削除しました",
                                    actionLabel = "元に戻す",
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
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchlistRow(item: WatchlistItem, onClick: () -> Unit) {
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
                "ウォッチリストは空",
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
