package io.github.shizukutanaka.popcoon.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Room エンティティ定義。
 *
 * 設計方針:
 *  - 検索履歴・お気に入り・キャッシュを 3 テーブルに分離 (責務単一)
 *  - 価格履歴は backend と冗長保持 (オフライン時に閲覧可能)
 *  - インデックス: 検索キーは sorted by recency
 */

// ── Entity: Watchlist (お気に入り) ──────────────────────────────────────────
@Entity(
    tableName = "watchlist",
    indices = [Index(value = ["addedAt"])],
)
data class WatchlistItem(
    @PrimaryKey val productKey: String,
    val sku: String,
    val title: String,
    val platform: String,
    val realPrice: Long,
    val listPrice: Long,
    val url: String,
    val imageUrl: String?,
    val addedAt: Long = Instant.now().toEpochMilli(),
    /**
     * ユーザー設定の目標価格（円）。null = 未設定。
     * 同期時にこの価格以下になったら、値下がり率に関係なくアラートを送る。
     * (v2 で追加 — MIGRATION_1_2)
     */
    val targetPrice: Long? = null,
    /**
     * ウォッチ追加時の価格（円）。追加後は同期で上書きしない（基準として固定）。
     * 「追加時からの変動」表示に使う。0 = 基準なし（v2 以前に追加されたアイテム）。
     * (v3 で追加 — MIGRATION_2_3)
     */
    val addedPrice: Long = 0,
    /**
     * 在庫アラートを有効にするか（商品ごとの設定）。
     * true のとき PriceSyncWorker がライブ在庫を取得し BACK_IN_STOCK を通知する。
     * (v5 で追加 — MIGRATION_4_5)
     */
    val stockAlertEnabled: Boolean = false,
    /**
     * 前回同期時の在庫状態。null = 初回同期で基準なし（通知しない）。
     * StockAlertEvaluator のエッジトリガ判定に使用。
     * (v5 で追加 — MIGRATION_4_5)
     */
    val previousInStock: Boolean? = null,
    /**
     * ユーザー定義のタグ（フォルダ分類）。null = 未分類。
     * 自由記述の単一タグ（フォルダ階層ではない — シンプルさ優先）。
     * WatchlistScreen のフィルタチップに使用。
     * (v6 で追加 — MIGRATION_5_6)
     */
    val tag: String? = null,
    /**
     * 価格アラートの「1同期サイクル遅延確認」で保留中の観測値（円）。null = 保留なし。
     * PriceAlertDebouncer が使用: 通知に値する値下がり/目標到達を検知しても即座には
     * 発火させず、この列に観測値を保存し次回同期で同じ値が再現した場合のみ発火する
     * (瞬間的なスクレイピングエラーによる誤通知対策、機能過不足監査で発見)。
     * (v7 で追加 — MIGRATION_6_7)
     */
    val pendingPrice: Long? = null,
)

// ── Entity: SearchHistory ───────────────────────────────────────────────────
@Entity(
    tableName = "search_history",
    indices = [Index(value = ["query"]), Index(value = ["timestamp"])],
)
data class SearchHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
)

// ── Entity: PriceCache (オフライン閲覧用) ──────────────────────────────────
@Entity(tableName = "price_cache")
data class PriceCacheEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productKey: String,
    val realPrice: Long,
    val listPrice: Long,
    val recordedAt: Long,
)

// ── Type converter: Instant ↔ Long ─────────────────────────────────────────
class InstantConverter {
    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun toInstant(epoch: Long?): Instant? = epoch?.let { Instant.ofEpochMilli(it) }
}

// ── DAO ─────────────────────────────────────────────────────────────────────
@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchlistItem>>

    @Query("SELECT * FROM watchlist WHERE productKey = :key LIMIT 1")
    suspend fun get(key: String): WatchlistItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WatchlistItem)

    /**
     * 複数件を 1 トランザクションで upsert する (バックアップ復元用)。
     * 1 件ずつ upsert すると途中失敗で部分的に書き換わり、呼び出し側が
     * 「失敗」と表示しているのに DB は変わっている、という状態が起きる。
     */
    @Transaction
    suspend fun upsertAll(items: List<WatchlistItem>) {
        items.forEach { upsert(it) }
    }

    @Query("DELETE FROM watchlist WHERE productKey = :key")
    suspend fun delete(key: String)

    /** 目標価格を設定 / 解除（null で解除）。 */
    @Query("UPDATE watchlist SET targetPrice = :target WHERE productKey = :key")
    suspend fun setTargetPrice(key: String, target: Long?)

    /** 現在価格のみ更新。upsert の全フィールド書き換えを避け addedPrice を保全する。 */
    @Query("UPDATE watchlist SET realPrice = :price WHERE productKey = :key")
    suspend fun updatePrice(key: String, price: Long)

    /**
     * 現在価格と、価格アラート確認待ち状態 (PriceAlertDebouncer) をまとめて更新する。
     * updatePrice() 単体だと pendingPrice が古いまま残ってしまうため、
     * デバウンス対応後の PriceSyncWorker はこちらを使う。
     */
    @Query("UPDATE watchlist SET realPrice = :price, pendingPrice = :pendingPrice WHERE productKey = :key")
    suspend fun updatePriceAndPending(key: String, price: Long, pendingPrice: Long?)

    /** 在庫アラートの on/off を設定する。 */
    @Query("UPDATE watchlist SET stockAlertEnabled = :enabled WHERE productKey = :key")
    suspend fun setStockAlertEnabled(key: String, enabled: Boolean)

    /** 同期後の在庫状態を保存（次回の StockAlertEvaluator エッジトリガ判定に使う）。 */
    @Query("UPDATE watchlist SET previousInStock = :wasInStock WHERE productKey = :key")
    suspend fun updateStockState(key: String, wasInStock: Boolean)

    /** タグ（フォルダ分類）を設定 / 解除（null で「未分類」に戻す）。 */
    @Query("UPDATE watchlist SET tag = :tag WHERE productKey = :key")
    suspend fun setTag(key: String, tag: String?)

    /** 現在使用中のタグ一覧（重複なし、フィルタチップ表示用）。 */
    @Query("SELECT DISTINCT tag FROM watchlist WHERE tag IS NOT NULL ORDER BY tag ASC")
    fun observeTags(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM watchlist")
    suspend fun count(): Int

    @Query("DELETE FROM watchlist")
    suspend fun deleteAll()
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<SearchHistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SearchHistoryEntry)

    /** 同じクエリは1つだけ残す。挿入後に重複削除 */
    @Query("DELETE FROM search_history WHERE query = :q AND id != (SELECT MAX(id) FROM search_history WHERE query = :q)")
    suspend fun deduplicate(q: String)

    @Query("DELETE FROM search_history WHERE id NOT IN (SELECT id FROM search_history ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 50)

    @Query("DELETE FROM search_history")
    suspend fun deleteAll()

    /** insert + deduplicate + trim を 1 トランザクションでアトミックに実行。 */
    @Transaction
    suspend fun insertAndDeduplicate(entry: SearchHistoryEntry, keep: Int = 50) {
        insert(entry)
        deduplicate(entry.query)
        trim(keep)
    }
}

@Dao
interface PriceCacheDao {
    @Query("SELECT * FROM price_cache WHERE productKey = :key ORDER BY recordedAt DESC LIMIT :limit")
    suspend fun getForProduct(key: String, limit: Int = 90): List<PriceCacheEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<PriceCacheEntry>)

    @Query("DELETE FROM price_cache WHERE recordedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM price_cache")
    suspend fun deleteAll()
}

// ── Database ────────────────────────────────────────────────────────────────
@Database(
    entities = [WatchlistItem::class, SearchHistoryEntry::class, PriceCacheEntry::class],
    version = 7,
    exportSchema = true,
)
@TypeConverters(InstantConverter::class)
abstract class PopcoonDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun priceCacheDao(): PriceCacheDao

    companion object {
        const val DB_NAME = "popcoon.db"

        /**
         * v1 → v2: watchlist に目標価格カラムを追加（希望価格アラート機能）。
         * nullable で追加するため既存行はそのまま（targetPrice = NULL = 未設定）。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watchlist ADD COLUMN targetPrice INTEGER")
            }
        }

        /**
         * v2 → v3: watchlist に追加時価格カラムを追加（「追加時からの変動」表示）。
         * NOT NULL DEFAULT 0 で追加。既存行は addedPrice = 0 (基準なし) のままとし、
         * UI 側で 0 を「データなし」として扱う。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watchlist ADD COLUMN addedPrice INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3 → v4: パフォーマンス改善のため検索用インデックスを追加。
         * watchlist.addedAt: 登録日時降順一覧の ORDER BY に使用。
         * search_history.query: 重複排除の WHERE query = ? に使用。
         * search_history.timestamp: 最新順取得の ORDER BY に使用。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_watchlist_addedAt` ON `watchlist` (`addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_history_query` ON `search_history` (`query`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_history_timestamp` ON `search_history` (`timestamp`)")
            }
        }

        /**
         * v4 → v5: watchlist に在庫アラート用 2 列を追加。
         * stockAlertEnabled: NOT NULL DEFAULT 0 (既存行は全て OFF)。
         * previousInStock: nullable (既存行は初回同期扱い = 基準なし)。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watchlist ADD COLUMN stockAlertEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watchlist ADD COLUMN previousInStock INTEGER")
            }
        }

        /**
         * v5 → v6: watchlist にタグ（フォルダ分類）列を追加。
         * nullable で追加するため既存行はそのまま（tag = NULL = 未分類）。
         * (機能過不足監査 B4: ウォッチリストのタグ/フォルダ分類が無かった、への対応)
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watchlist ADD COLUMN tag TEXT")
                // price_cache (オフライン閲覧用) は v6 で **同時に** 追加されたテーブルだが、
                // この移行に CREATE TABLE が無かった。v5 以前の DB を v7 まで上げると
                // テーブルが存在しないまま Room のスキーマ検証に到達し、
                // IllegalStateException でアプリが起動不能になる (release ビルドは
                // ユーザーデータ保全のため破壊的フォールバックを意図的に無効化している)。
                //
                // 現時点では到達不能な経路ではある — このリポジトリの履歴は v6 から始まり、
                // GitHub リリースは 0 件で、v5 以前の DB を持つ端末は存在しない
                // (開発中の debug ビルドは fallbackToDestructiveMigration で作り直される)。
                // それでも「起動不能」という失敗の重さに対して修正が 1 文で済むので塞ぐ。
                // DDL は Room が @Entity PriceCacheEntry から生成する形に合わせてある
                // (Long の autoGenerate 主キー = INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_cache` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`productKey` TEXT NOT NULL, " +
                        "`realPrice` INTEGER NOT NULL, " +
                        "`listPrice` INTEGER NOT NULL, " +
                        "`recordedAt` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v6 → v7: watchlist に価格アラート確認待ち列を追加。
         * nullable で追加するため既存行はそのまま（pendingPrice = NULL = 保留なし）。
         * PriceAlertDebouncer の「1同期サイクル遅延確認」に使用 (機能過不足監査で発見:
         * 瞬間的なスクレイピングエラーで誤った値下がり通知/目標到達通知が即座に発火していた)。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watchlist ADD COLUMN pendingPrice INTEGER")
            }
        }
    }
}
