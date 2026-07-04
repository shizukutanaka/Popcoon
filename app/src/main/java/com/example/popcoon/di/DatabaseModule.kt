package com.example.popcoon.di

import android.content.Context
import androidx.room.Room
import com.example.popcoon.BuildConfig
import com.example.popcoon.data.db.PopcoonDatabase
import com.example.popcoon.data.db.PriceCacheDao
import com.example.popcoon.data.db.SearchHistoryDao
import com.example.popcoon.data.db.WatchlistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PopcoonDatabase {
        return Room.databaseBuilder(
            context, PopcoonDatabase::class.java, PopcoonDatabase.DB_NAME,
        )
            // 明示マイグレーションでユーザーデータを保全する。
            // 新スキーマ導入時はここに .addMigrations(MIGRATION_x_y) を追加すること。
            .addMigrations(
                PopcoonDatabase.MIGRATION_1_2,
                PopcoonDatabase.MIGRATION_2_3,
                PopcoonDatabase.MIGRATION_3_4,
                PopcoonDatabase.MIGRATION_4_5,
                PopcoonDatabase.MIGRATION_5_6,
            )
            // debug のみ破壊的フォールバックを許可。
            // release ではスキーマ変更時に明示 Migration を必須化し、
            // ユーザーのウォッチリスト/価格履歴を黙って消さない (クラッシュで検知)。
            .apply { if (BuildConfig.DEBUG) fallbackToDestructiveMigration() }
            .build()
    }

    @Provides @Singleton
    fun provideWatchlistDao(db: PopcoonDatabase): WatchlistDao = db.watchlistDao()

    @Provides @Singleton
    fun provideSearchHistoryDao(db: PopcoonDatabase): SearchHistoryDao =
        db.searchHistoryDao()

    @Provides @Singleton
    fun providePriceCacheDao(db: PopcoonDatabase): PriceCacheDao = db.priceCacheDao()
}
