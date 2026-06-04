package com.example.popcoon.ui.screens.watchlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.popcoon.data.db.WatchlistDao
import com.example.popcoon.data.db.WatchlistItem
import com.example.popcoon.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: WatchlistDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val items: Flow<List<WatchlistItem>> = dao.observeAll()

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

    private suspend fun updateWidget() {
        val current = items.first()
        WidgetUpdater.update(context, current)
    }
}
