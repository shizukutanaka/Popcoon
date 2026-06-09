package com.example.popcoon.di

import com.example.popcoon.data.network.AmazonPaApiClient
import com.example.popcoon.data.network.FallbackScraper
import com.example.popcoon.data.network.RakutenClient
import com.example.popcoon.data.network.YahooClient
import com.example.popcoon.data.repository.BackendClient
import com.example.popcoon.data.repository.IProductRepository
import com.example.popcoon.data.repository.ProductRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * ネットワーク層の Hilt バインディング。
 *
 * @Inject constructor を持つ型 (BackendClient / AdviceCache / BuyingAdvisor /
 * PriceHistoryCsvExporter 等) はここで @Provides しない — 二重バインディング
 * (Dagger コンパイルエラー) になるため。Hilt が constructor から直接生成する。
 * ここに残すのは @Inject を持たない外部 API クライアントと、インターフェース束縛のみ。
 */
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

    // ProductRepository は IProductRepository インターフェースとして束縛する
    // (@Inject constructor は concrete 型を供給するが、利用側は interface を要求)。
    @Provides @Singleton
    fun provideProductRepository(
        amazon: AmazonPaApiClient,
        rakuten: RakutenClient,
        yahoo: YahooClient,
        fallback: FallbackScraper,
        backend: BackendClient,
    ): IProductRepository = ProductRepository(amazon, rakuten, yahoo, fallback, backend)
}
