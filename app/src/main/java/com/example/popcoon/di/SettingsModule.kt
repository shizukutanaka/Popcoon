package com.example.popcoon.di

import android.content.Context
import com.example.popcoon.feature.crash.PrivacyCrashReporter
import com.example.popcoon.feature.crash.StartupTracker
import com.example.popcoon.feature.retention.ReviewPrompter
import com.example.popcoon.feature.settings.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

import com.example.popcoon.ui.util.ConnectivityObserver

/**
 * 設定・クラッシュ・リテンション・ネットワーク関連の Hilt バインディング。
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    // UserPreferences は @Inject constructor を持つため Hilt が直接提供できる。
    // ここで @Provides すると二重バインディング (Dagger コンパイルエラー) になるため置かない。

    @Provides @Singleton
    fun providePrivacyCrashReporter(
        @ApplicationContext context: Context,
    ): PrivacyCrashReporter = PrivacyCrashReporter(context)

    @Provides @Singleton
    fun provideStartupTracker(): StartupTracker = StartupTracker()

    @Provides @Singleton
    fun provideReviewPrompter(
        prefs: UserPreferences,
    ): ReviewPrompter = ReviewPrompter(prefs)

    @Provides @Singleton
    fun provideConnectivityObserver(
        @ApplicationContext context: Context,
    ): ConnectivityObserver = ConnectivityObserver(context)
}
