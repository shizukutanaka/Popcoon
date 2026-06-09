package com.example.popcoon.ui.screens.barcode

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing
import com.example.popcoon.feature.barcode.BarcodeScanner
import com.example.popcoon.feature.barcode.JanCodeQuery
import com.example.popcoon.feature.share.UrlClassifier

/**
 * バーコードスキャン画面。
 *
 * 同種ソフト調査:
 *  - 最安値.com: バーコードを読み取ってから5秒で結果表示 (最大の差別化)
 *  - Keepa: built-in barcode scanner でリアルタイム検索
 *  - Pricey: バーコードスキャン対応
 *  - 価格.com: バーコード検索機能
 *
 * Popcoon 実装:
 *  - Google Code Scanner API → CAMERA 権限不要
 *  - JAN-13/JAN-8/QR/URL の自動判別
 *  - QR/URL は Share Intent 経由の URL 分類で EC 商品ページに対応
 *  - 結果は即座に SearchScreen の query に渡す
 */
@Composable
fun BarcodeScreen(
    onQueryResult: (String) -> Unit,     // JAN → SearchScreen query
    onProductResult: (String) -> Unit,   // EC URL → ProductDetail
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scanner = remember { BarcodeScanner() }
    var scanState by remember { mutableStateOf<ScanState>(ScanState.Idle) }

    // Activity は非 null 前提
    val activity = context as? Activity

    LaunchedEffect(Unit) {
        if (activity == null) {
            scanState = ScanState.Error(context.getString(R.string.barcode_camera_failed))
            return@LaunchedEffect
        }
        scanner.bind(activity)
        scanState = ScanState.Scanning

        val task = scanner.startScan() ?: run {
            scanState = ScanState.Error(context.getString(R.string.barcode_unavailable))
            return@LaunchedEffect
        }

        task.addOnSuccessListener { barcode ->
            val result = BarcodeScanner.fromBarcode(barcode)
            when {
                // JAN/EAN → 商品検索クエリ
                result.isJanLike -> {
                    val query = JanCodeQuery.toSearchQuery(result.rawValue)
                    if (query != null) {
                        scanState = ScanState.Success(query)
                        onQueryResult(query)
                    } else {
                        scanState = ScanState.Error(
                            "${context.getString(R.string.barcode_invalid)}: ${result.rawValue}",
                        )
                    }
                }
                // URL → EC 商品ページ直接遷移
                result.isUrl -> {
                    val classified = UrlClassifier.classify(result.rawValue)
                    if (classified != null) {
                        val key = "${classified.platform.id}:${classified.sku}"
                        scanState = ScanState.Success(result.rawValue)
                        onProductResult(key)
                    } else {
                        // EC 以外の URL → クエリとして検索
                        scanState = ScanState.Success(result.rawValue)
                        onQueryResult(result.rawValue)
                    }
                }
                // その他 (CODE_128 等) → キーワード検索
                else -> {
                    scanState = ScanState.Success(result.rawValue)
                    onQueryResult(result.rawValue)
                }
            }
        }.addOnFailureListener { e ->
            // ユーザーキャンセル or エラー → 検索画面に戻る
            val msg = e.message ?: ""
            if (msg.contains("cancelled", ignoreCase = true) ||
                msg.contains("CANCELED", ignoreCase = true)) {
                onBack()
            } else {
                scanState = ScanState.Error(context.getString(R.string.barcode_error))
            }
        }
    }

    // UI: Google Code Scanner はシステム UI なので
    // この画面はローディングインジケーター / エラーフォールバックのみ
    ScanFeedbackUI(
        state = scanState,
        onRetry = {
            scanState = ScanState.Idle
            // LaunchedEffect は key Unit のため再実行不可 → Back で再表示
            onBack()
        },
        onBack = onBack,
    )
}

private sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Success(val value: String) : ScanState
    data class Error(val message: String) : ScanState
}

@Composable
private fun ScanFeedbackUI(
    state: ScanState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
            modifier = Modifier.padding(Spacing.ml),
        ) {
            when (state) {
                ScanState.Idle, ScanState.Scanning -> {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.barcode_point_camera),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                is ScanState.Success -> {
                    Text("✓", style = MaterialTheme.typography.displayMedium)
                    Text(
                        stringResource(R.string.barcode_read_success, state.value.take(30)),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                is ScanState.Error -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(CornerRadius.card),
                    ) {
                        Column(
                            Modifier.padding(Spacing.ml),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(Spacing.ml))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.ml)) {
                                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.nav_back)) }
                                Button(onClick = onRetry) { Text(stringResource(R.string.barcode_retry)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
