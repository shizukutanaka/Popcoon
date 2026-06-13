package com.example.popcoon.ui.screens.watchlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.popcoon.data.db.WatchlistDao
import com.example.popcoon.data.db.WatchlistItem
import com.example.popcoon.feature.cart.SmartCartService
import com.example.popcoon.feature.settings.UserPreferences
import com.example.popcoon.feature.watchlist.WatchlistSort
import com.example.popcoon.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: WatchlistDao,
    private val prefs: UserPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val rawItems: Flow<List<WatchlistItem>> = dao.observeAll()

    /** 現在の並べ替えモード（永続化された設定から復元）。 */
    val sortMode: Flow<WatchlistSort.Mode> = prefs.watchlistSortOrdinal
        .map { ordinal ->
            WatchlistSort.Mode.entries.getOrElse(ordinal) { WatchlistSort.Mode.ADDED_DESC }
        }

    /** 並べ替え適用済みのウォッチリスト。画面はこれを購読する。 */
    val items: Flow<List<WatchlistItem>> = combine(rawItems, sortMode) { list, mode ->
        WatchlistSort.sort(list, mode)
    }

    /**
     * ウォッチリスト全体の横断カート最適化結果。
     * 2件以上あれば自動計算。最適化は総当たり (最大 200k 通り) になり得るため
     * Dispatchers.Default に逃がし、メインスレッドをブロックしない。
     * 並べ替え順は最適化結果に影響しないため raw を使う。
     */
    val smartCart = rawItems
        .map { list -> if (list.size >= 2) SmartCartService.optimize(list) else null }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSortMode(mode: WatchlistSort.Mode) {
        viewModelScope.launch { prefs.setWatchlistSort(mode.ordinal) }
    }

    fun remove(productKey: String) {
        viewModelScope.launch {
            dao.delete(productKey)
            updateWidget()
        }
    }

    fun add(item: WatchlistItem) {
        viewModelScope.launch {
            dao.upsert(item)
            updateWidget()
        }
    }

    /**
     * 目標価格を設定 / 解除する。
     * 次回の価格同期で、この価格以下になったら値下がり率に関係なく通知される。
     * @param target null で解除。
     */
    fun setTargetPrice(productKey: String, target: Long?) {
        viewModelScope.launch {
            dao.setTargetPrice(productKey, target)
        }
    }

    /**
     * 在庫変化アラートを有効 / 無効にする。
     * 次回の価格同期で在庫状態が変化したとき通知される。
     */
    fun setStockAlert(productKey: String, enabled: Boolean) {
        viewModelScope.launch {
            dao.setStockAlert(productKey, enabled)
        }
    }

    private suspend fun updateWidget() {
        val current = rawItems.first()
        WidgetUpdater.update(context, current)
    }
}
