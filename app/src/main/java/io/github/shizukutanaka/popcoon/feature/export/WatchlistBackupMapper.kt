package io.github.shizukutanaka.popcoon.feature.export

import io.github.shizukutanaka.popcoon.data.db.WatchlistItem

// WatchlistBackupManager.kt (android.content / FileProvider / Hilt) から切り出した純関数。
// バックアップ⇄復元の往復マッピングはデータ欠落の直撃点だが、同居していると
// Android 依存に巻き込まれて実コンパイルも kotest (WatchlistBackupManagerTest) も
// 走らなかった。同一パッケージなので呼び出し側は無変更。

internal fun WatchlistItem.toBackupEntry(): WatchlistBackupEntry = WatchlistBackupEntry(
    productKey = productKey,
    sku = sku,
    title = title,
    platform = platform,
    realPrice = realPrice,
    listPrice = listPrice,
    url = url,
    imageUrl = imageUrl,
    addedAt = addedAt,
    targetPrice = targetPrice,
    addedPrice = addedPrice,
    stockAlertEnabled = stockAlertEnabled,
    tag = tag,
)

internal fun WatchlistBackupEntry.toWatchlistItem(): WatchlistItem = WatchlistItem(
    productKey = productKey,
    sku = sku,
    title = title,
    platform = platform,
    realPrice = realPrice,
    listPrice = listPrice,
    url = url,
    imageUrl = imageUrl,
    addedAt = addedAt,
    targetPrice = targetPrice,
    addedPrice = addedPrice,
    stockAlertEnabled = stockAlertEnabled,
    previousInStock = null,
    tag = tag,
)
