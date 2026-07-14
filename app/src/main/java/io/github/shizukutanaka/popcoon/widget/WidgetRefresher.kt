package io.github.shizukutanaka.popcoon.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import javax.inject.Inject

/**
 * WidgetUpdater.update() を Context を持たない ViewModel からも呼べるようにする薄いラッパー。
 *
 * plain JVM ユニットテスト (Robolectric 不使用) では android.content.Context のインスタンス化が
 * 常に失敗するため、Context を直接コンストラクタで要求する ViewModel はテスト不能になる
 * (WatchlistViewModel がテストカバレッジゼロだった根本原因、機能過不足監査で発見)。
 * インタフェース化することで ViewModel は IWidgetRefresher にのみ依存し、テストでは
 * no-op のフェイクに差し替えられる (IUserPreferences と同方針)。
 */
interface IWidgetRefresher {
    fun refresh(items: List<WatchlistItem>)
}

class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) : IWidgetRefresher {
    override fun refresh(items: List<WatchlistItem>) {
        WidgetUpdater.update(context, items)
    }
}
