package io.github.shizukutanaka.popcoon.feature.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.github.shizukutanaka.popcoon.data.db.WatchlistDao
import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
        /**
         * @property count 実際に DB へ書き込んだ件数。
         * @property skipped 主キーが壊れていて復元できなかった件数 ([normalizedOrNull] 参照)。
         *   0 でない場合はバックアップファイル側が壊れている。
         */
        data class Success(val count: Int, val skipped: Int = 0) : ImportResult
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
            // ハードコードされた日本語件名は EN/KO/ZH ロケールに漏れていた
            // (商用リリース監査で発見)。context があるので直接 getString で解決する。
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.watchlist_backup_share_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * ファイルピッカーで選択された URI からウォッチリストを復元する。
     * 失敗 (読み込み不能・不正な JSON・DB 書き込みエラー) しても既存データは一切変更しない
     * — 書き込みは [WatchlistDao.upsertAll] の単一トランザクションで行う。
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

        // DB へ入れる前に一度濾す。バックアップ JSON は共有・クラウド保存・手編集を
        // 経て戻りうる唯一の外部入力で、壊れた行をそのまま upsert すると主キーが壊れる。
        val restorable = entries.mapNotNull { it.normalizedOrNull() }
        val skipped = entries.size - restorable.size
        if (skipped > 0) {
            PopcoonLogger.w(this, "ウォッチリスト復元: 復元不能なエントリを除外 $skipped/${entries.size} 件")
        }

        // **1 トランザクションで書く**。以前は forEach で 1 件ずつ upsert しており、
        // 途中で失敗すると「復元失敗」と表示されながら DB は部分的に書き換わっていた
        // (このクラスの「失敗しても既存データは一切変更しない」という宣言と矛盾していた)。
        return try {
            watchlistDao.upsertAll(restorable.map { it.toWatchlistItem() })
            ImportResult.Success(restorable.size, skipped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PopcoonLogger.w(this, "ウォッチリスト復元: DB 書き込み失敗 ${e.message}", e)
            ImportResult.Failure("write_error")
        }
    }
}
