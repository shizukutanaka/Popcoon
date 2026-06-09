package com.example.popcoon.feature.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.popcoon.data.db.WatchlistDao
import com.example.popcoon.data.repository.BackendClient
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 価格履歴 CSV エクスポート — Premium 限定機能。
 *
 * 出力フォーマット:
 * 商品キー, タイトル, プラットフォーム, 記録日時, 表示価格, 実売価格
 *
 * 使用方法:
 * 1. `generate()` で File を取得
 * 2. ShareSheet で共有 (FileProvider 経由)
 * 3. 標準の CSV アプリ / Numbers / Excel で開ける
 *
 * プライバシー: エクスポートファイルはユーザーが Share するまで端末内のみに留まる。
 */
@Singleton
class PriceHistoryCsvExporter @Inject constructor(
    private val watchlistDao: WatchlistDao,
    private val backend: BackendClient,
) {
    private val formatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Tokyo"))

    /**
     * 全ウォッチリスト商品の価格履歴を CSV として生成。
     * @return 生成した CSV ファイルの URI (FileProvider 経由)
     */
    suspend fun generate(context: Context): Uri? {
        val watchlist = runCatching {
            watchlistDao.observeAll().first()
        }.getOrDefault(emptyList())

        if (watchlist.isEmpty()) return null

        val sb = StringBuilder()
        sb.appendLine("商品キー,タイトル,プラットフォーム,記録日時(JST),表示価格(円),実売価格(円)")

        for (item in watchlist) {
            val history = runCatching {
                backend.getPriceHistory(item.productKey)
            }.getOrDefault(emptyList())

            for (record in history) {
                val dateStr = formatter.format(record.recordedAt)
                // CSV インジェクション対策: フィールドを必ずクォート
                sb.appendLine(
                    listOf(
                        item.productKey.csvEscape(),
                        item.title.csvEscape(),
                        item.platform.csvEscape(),
                        dateStr.csvEscape(),
                        record.listPrice.toString(),
                        record.realPrice.toString(),
                    ).joinToString(",")
                )
            }
        }

        // キャッシュディレクトリに書き込み。古い CSV ファイルを先にクリーンアップ。
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        val fileName = "popcoon_history_${System.currentTimeMillis()}.csv"
        val file = File(dir, fileName)
        file.writeText(sb.toString(), Charsets.UTF_8)

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /**
     * Share Sheet を起動するインテントを生成。
     */
    suspend fun shareIntent(context: Context): Intent? {
        val uri = generate(context) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Popcoon 価格履歴データ")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

}

/** 表計算ソフトで数式として解釈され得る先頭文字。 */
internal const val CSV_FORMULA_TRIGGERS = "=+-@\t\r"

/**
 * CSV フィールドを RFC 4180 準拠でエスケープし、数式インジェクションも防ぐ。
 * internal にしてテストから直接呼べるようにする。
 */
internal fun String.csvEscape(): String {
    // CSV インジェクション対策 (1): 数式起動文字で始まるフィールドは ' を前置
    val guarded = if (isNotEmpty() && first() in CSV_FORMULA_TRIGGERS) "'$this" else this
    // CSV インジェクション対策 (2): ダブルクォートをエスケープしてフィールドをクォート
    val escaped = guarded.replace("\"", "\"\"")
    return "\"$escaped\""
}
