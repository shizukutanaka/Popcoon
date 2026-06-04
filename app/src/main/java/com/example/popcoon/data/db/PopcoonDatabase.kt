package com.example.popcoon.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
@Entity(tableName = "watchlist")
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
)

// ── Entity: SearchHistory ───────────────────────────────────────────────────
@Entity(tableName = "search_history")
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
    version = 1,
    exportSchema = true,
)
@TypeConverters(InstantConverter::class)
abstract class PopcoonDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun priceCacheDao(): PriceCacheDao

    companion object {
        const val DB_NAME = "popcoon.db"
    }
}
