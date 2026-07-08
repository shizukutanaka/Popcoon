package io.github.shizukutanaka.popcoon.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Coil3 ImageLoader の最適化設定。
 *
 * デフォルト Coil 設定の問題:
 *  - メモリキャッシュは利用可能 RAM の 25% (大量の商品画像で OOM リスク)
 *  - ディスクキャッシュ未設定なので毎回ネットワーク取得
 *  - クロスフェードなしでチカチカ
 *
 * 改善:
 *  - メモリキャッシュ: 50MB (商品サムネイル数百枚に最適)
 *  - ディスクキャッシュ: 200MB (オフライン閲覧支援)
 *  - クロスフェード: 200ms (Apple 流のスムーズ遷移)
 *  - OkHttp タイムアウト: 接続 5s + 読み取り 10s (商品画像は軽量)
 */
@Module
@InstallIn(SingletonComponent::class)
object CoilImageLoaderModule {

    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(50L * 1024 * 1024)  // 50MB
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache"))
                .maxSizeBytes(200L * 1024 * 1024)  // 200MB
                .build()
        }
        .crossfade(200)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
        }
        .build()
}
