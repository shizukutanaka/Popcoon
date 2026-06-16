package com.example.popcoon.di

import com.example.popcoon.feature.settings.IUserPreferences
import com.example.popcoon.feature.settings.UserPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UserPreferencesModule {

    @Binds
    @Singleton
    abstract fun bindUserPreferences(impl: UserPreferences): IUserPreferences
}
