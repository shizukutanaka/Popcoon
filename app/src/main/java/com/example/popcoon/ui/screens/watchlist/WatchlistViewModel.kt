package com.example.popcoon.ui.screens.watchlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.popcoon.data.db.WatchlistDao
import com.example.popcoon.data.db.WatchlistItem
import com.example.popcoon.feature.cart.SmartCartService
import com.example.popcoon.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: WatchlistDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val items: Flow<List<WatchlistItem>> = dao.observeAll()

    /**
     * ウォッチリスト全体の横断カート最適化結果。
     * 2件以上あれば自動計算（純関数 → 高速、ブロックなし）。
     */
    val smartCart = items
        .map { list -> if (list.size >= 2) SmartCartService.optimize(list) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    private suspend fun updateWidget() {
        val current = items.first()
        WidgetUpdater.update(context, current)
    }
}
