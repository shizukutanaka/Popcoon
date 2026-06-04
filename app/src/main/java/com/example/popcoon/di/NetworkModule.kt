package com.example.popcoon.di

import com.example.popcoon.data.network.AmazonPaApiClient
import com.example.popcoon.data.network.FallbackScraper
import com.example.popcoon.data.network.RakutenClient
import com.example.popcoon.data.network.YahooClient
import com.example.popcoon.data.repository.BackendClient
import com.example.popcoon.data.repository.IProductRepository
import com.example.popcoon.data.repository.ProductRepository
import com.example.popcoon.feature.ai.BuyingAdvisor
import com.example.popcoon.feature.export.PriceHistoryCsvExporter
import com.example.popcoon.data.db.WatchlistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun provideAmazonClient(): AmazonPaApiClient = AmazonPaApiClient()

    @Provides @Singleton
    fun provideRakutenClient(): RakutenClient = RakutenClient()

    @Provides @Singleton
    fun provideYahooClient(): YahooClient = YahooClient()

    @Provides @Singleton
    fun provideFallbackScraper(): FallbackScraper = FallbackScraper()

    @Provides @Singleton
    fun provideAdviceCache(): com.example.popcoon.feature.ai.AdviceCache =
        com.example.popcoon.feature.ai.AdviceCache()

    @Provides @Singleton
    fun provideBuyingAdvisor(
        cache: com.example.popcoon.feature.ai.AdviceCache,
    ): com.example.popcoon.feature.ai.BuyingAdvisor =
        com.example.popcoon.feature.ai.BuyingAdvisor(cache)

    @Provides @Singleton
    fun provideBackendClient(): BackendClient = BackendClient()

    @Provides @Singleton
    fun provideProductRepository(
        amazon: AmazonPaApiClient,
        rakuten: RakutenClient,
        yahoo: YahooClient,
        fallback: FallbackScraper,
        backend: BackendClient,
    ): IProductRepository = ProductRepository(amazon, rakuten, yahoo, fallback, backend)

    @Provides @Singleton
    fun providePriceHistoryCsvExporter(
        watchlistDao: WatchlistDao,
        backend: BackendClient,
    ): PriceHistoryCsvExporter = PriceHistoryCsvExporter(watchlistDao, backend)
}
