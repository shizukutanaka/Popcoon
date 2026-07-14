package io.github.shizukutanaka.popcoon.di

import io.github.shizukutanaka.popcoon.widget.IWidgetRefresher
import io.github.shizukutanaka.popcoon.widget.WidgetRefresher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetRefresherModule {

    @Binds
    @Singleton
    abstract fun bindWidgetRefresher(impl: WidgetRefresher): IWidgetRefresher
}
