package com.example.popcoon.data.db

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
     * 在庫変化アラートの有効フラグ。true のとき「在庫復活 / 在庫切れ」を通知する。
     * (v5 で追加 — MIGRATION_4_5)
     */
    val stockAlertEnabled: Boolean = false,
    /**
     * 前回同期時の在庫状態キャッシュ。null = 初回同期 (基準なし)。
     * (v5 で追加 — MIGRATION_4_5)
     */
    val lastKnownInStock: Boolean? = null,
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

    @Query("DELETE FROM watchlist WHERE productKey = :key")
    suspend fun delete(key: String)

    /** 目標価格を設定 / 解除（null で解除）。 */
    @Query("UPDATE watchlist SET targetPrice = :target WHERE productKey = :key")
    suspend fun setTargetPrice(key: String, target: Long?)

    /** 現在価格のみ更新。upsert の全フィールド書き換えを避け addedPrice を保全する。 */
    @Query("UPDATE watchlist SET realPrice = :price WHERE productKey = :key")
    suspend fun updatePrice(key: String, price: Long)

    /** 在庫アラート有効/無効を切り替える。 */
    @Query("UPDATE watchlist SET stockAlertEnabled = :enabled WHERE productKey = :key")
    suspend fun setStockAlert(key: String, enabled: Boolean)

    /** 前回同期の在庫状態を更新する (StockAlertEvaluator の比較基準)。 */
    @Query("UPDATE watchlist SET lastKnownInStock = :inStock WHERE productKey = :key")
    suspend fun updateLastKnownInStock(key: String, inStock: Boolean)

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
    version = 5,
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
         * v4 → v5: 在庫アラート機能の追加。
         * stockAlertEnabled: ユーザーが商品ごとに有効化するフラグ (デフォルト: 0=false)。
         * lastKnownInStock: 前回同期時の在庫状態キャッシュ (null=初回同期)。
         * nullable INTEGER を使い、NULL=未記録 / 0=在庫なし / 1=在庫あり の三値で管理。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watchlist ADD COLUMN stockAlertEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watchlist ADD COLUMN lastKnownInStock INTEGER")
            }
        }
    }
}
