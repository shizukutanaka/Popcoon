package io.github.shizukutanaka.popcoon.feature.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.github.shizukutanaka.popcoon.data.db.WatchlistDao
import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.github.shizukutanaka.popcoon.data.repository.BackendClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
     * エクスポート結果。
     *
     * @property uri 生成した CSV ファイルの URI (FileProvider 経由)。
     * @property failedItems 価格履歴の取得に失敗し、**CSV に 1 行も含まれなかった**商品数。
     *   0 でなければ出力は不完全 — 呼び出し側は必ずユーザーに伝えること。
     */
    data class Export(val uri: Uri, val failedItems: Int)

    /**
     * 全ウォッチリスト商品の価格履歴を CSV として生成。
     *
     * 取得は [MAX_CONCURRENCY] 並列 (CLAUDE.md パターン 4「無制限 fan-out 禁止」)。
     * 以前は 1 商品ずつ逐次に HTTP を叩いており、ウォッチ数に比例して待たされた。
     *
     * **失敗を黙って捨てない**: 以前は商品ごとに `getOrDefault(emptyList())` で
     * 握り潰しており、通信が半分失敗しても「その商品の履歴は 0 件」として
     * 何事もなかったかのように CSV が出来上がっていた。ユーザーは欠けていることを
     * 知らないまま完全なデータだと思って表計算ソフトで分析する。
     * 失敗件数を [Export.failedItems] で返し、呼び出し側が伝える。
     */
    suspend fun generate(context: Context): Export? {
        val watchlist = runCatching {
            watchlistDao.observeAll().first()
        }.onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())

        if (watchlist.isEmpty()) return null

        // 取得は並列、CSV への書き出しは元のウォッチリスト順を保つ (決定的な出力にする)。
        val semaphore = Semaphore(MAX_CONCURRENCY)
        val fetched: List<Result<List<PriceRecord>>> = coroutineScope {
            watchlist.map { item ->
                async {
                    semaphore.withPermit {
                        ensureActive()
                        runCatching { backend.getPriceHistory(item.productKey) }
                            .onFailure { if (it is CancellationException) throw it }
                    }
                }
            }.awaitAll()
        }

        val sb = StringBuilder()
        // ヘッダー行はスプレッドシートアプリ (Excel/Numbers/Google Sheets) で開かれるため、
        // 以前の日本語固定ヘッダーは EN/KO/ZH ロケールに漏れていた (商用リリース監査で発見)。
        sb.appendLine(context.getString(R.string.csv_export_header))

        var failedItems = 0
        for ((index, item) in watchlist.withIndex()) {
            val history = fetched[index].getOrElse {
                failedItems++
                PopcoonLogger.w(this, "CSV エクスポート: 価格履歴の取得に失敗 (${it.message})")
                continue
            }
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

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Export(uri, failedItems)
    }

    /** Share Sheet 用のインテントと、取得に失敗した商品数。 */
    data class ShareRequest(val intent: Intent, val failedItems: Int)

    /**
     * Share Sheet を起動するインテントを生成。
     * 取得に失敗した商品があれば [ShareRequest.failedItems] に件数が入る —
     * 呼び出し側はこれをユーザーに伝えること (不完全な CSV を黙って渡さない)。
     */
    suspend fun shareIntent(context: Context): ShareRequest? {
        val export = generate(context) ?: return null
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, export.uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.csv_export_share_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return ShareRequest(intent, export.failedItems)
    }

    private companion object {
        /** 価格履歴取得の最大並列数 (backend への thundering herd 抑制、PriceSyncWorker と同値)。 */
        const val MAX_CONCURRENCY = 8
    }
}
