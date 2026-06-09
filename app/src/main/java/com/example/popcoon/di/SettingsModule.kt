package com.example.popcoon.di

import android.content.Context
import com.example.popcoon.feature.crash.PrivacyCrashReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 設定・クラッシュ関連の Hilt バインディング。
 *
 * UserPreferences / StartupTracker / ReviewPrompter / ConnectivityObserver は
 * いずれも @Inject constructor を持つため Hilt が直接生成する。ここで @Provides
 * すると二重バインディング (Dagger コンパイルエラー) になるため置かない。
 * PrivacyCrashReporter のみ @Inject を持たない (backendUrl 既定引数つき constructor)
 * ため、ここで明示的に提供する。
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides @Singleton
    fun providePrivacyCrashReporter(
        @ApplicationContext context: Context,
    ): PrivacyCrashReporter = PrivacyCrashReporter(context)
}
