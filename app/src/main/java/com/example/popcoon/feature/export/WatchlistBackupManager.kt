package com.example.popcoon.feature.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.popcoon.core.PopcoonLogger
import com.example.popcoon.data.db.WatchlistDao
import com.example.popcoon.data.db.WatchlistItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ウォッチリストのバックアップ/復元 (JSON、全ユーザー無料)。
 *
 * `PriceHistoryCsvExporter` (Premium 限定) とは別機能: あちらは価格履歴の
 * *分析用データ抽出* が目的で `WatchlistItem` の全フィールドを含まない
 * (機種変更時の復元には使えない)。こちらは `WatchlistItem` を過不足なく
 * JSON にシリアライズし、機種変更・再インストール時の完全復元を可能にする
 * (機能過不足監査 B5: エクスポートのみでインポート手段が無かった、への対応)。
 *
 * Room エンティティを直接 `@Serializable` にせず専用 DTO を介するのは、
 * `PriceRecord`/`PriceCacheEntry` と同じ既存方針 (Room スキーマとシリアライズ
 * 形式を分離し、将来の Room マイグレーションがバックアップ形式に影響しないようにする)。
 *
 * 復元は upsert のみ (追加的マージ)。既存アイテムは productKey が一致すれば上書きされるが、
 * 削除は一切行わない — 復元操作でユーザーの現在のウォッチリストが失われることはない。
 */
@Singleton
class WatchlistBackupManager @Inject constructor(
    private val watchlistDao: WatchlistDao,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    sealed interface ImportResult {
        data class Success(val count: Int) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    /**
     * バックアップファイルを生成。
     * @return 生成した JSON ファイルの URI (FileProvider 経由)。ウォッチリストが空なら null。
     */
    suspend fun export(context: Context): Uri? {
        val items = watchlistDao.observeAll().first()
        if (items.isEmpty()) return null

        val entries = items.map { it.toBackupEntry() }
        val jsonText = json.encodeToString(ListSerializer(WatchlistBackupEntry.serializer()), entries)

        val dir = File(context.cacheDir, "backups").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        val file = File(dir, "popcoon_watchlist_backup_${System.currentTimeMillis()}.json")
        file.writeText(jsonText, Charsets.UTF_8)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Share Sheet を起動するインテントを生成。 */
    suspend fun shareIntent(context: Context): Intent? {
        val uri = export(context) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Popcoon ウォッチリスト バックアップ")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * ファイルピッカーで選択された URI からウォッチリストを復元する。
     * 失敗 (不正な JSON、読み込み不能等) しても既存データは一切変更しない。
     */
    suspend fun import(context: Context, uri: Uri): ImportResult {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PopcoonLogger.w(this, "ウォッチリスト復元: ファイル読み込み失敗 ${e.message}", e)
            null
        } ?: return ImportResult.Failure("file_read_error")

        val entries = try {
            json.decodeFromString(ListSerializer(WatchlistBackupEntry.serializer()), text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PopcoonLogger.w(this, "ウォッチリスト復元: JSON 解析失敗 ${e.message}", e)
            return ImportResult.Failure("parse_error")
        }

        entries.forEach { watchlistDao.upsert(it.toWatchlistItem()) }
        return ImportResult.Success(entries.size)
    }
}

/**
 * バックアップ専用のシリアライズ可能 DTO。`WatchlistItem` の Room アノテーションと分離する。
 * `previousInStock` (内部同期状態) は意図的に含めない — 復元後は次回同期で自然に再構築される。
 */
@Serializable
internal data class WatchlistBackupEntry(
    val productKey: String,
    val sku: String,
    val title: String,
    val platform: String,
    val realPrice: Long,
    val listPrice: Long,
    val url: String,
    val imageUrl: String? = null,
    val addedAt: Long = 0,
    val targetPrice: Long? = null,
    val addedPrice: Long = 0,
    val stockAlertEnabled: Boolean = false,
)

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
)
