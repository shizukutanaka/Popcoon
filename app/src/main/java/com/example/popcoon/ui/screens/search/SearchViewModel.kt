package com.example.popcoon.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.popcoon.core.Trie
import com.example.popcoon.data.db.SearchHistoryDao
import com.example.popcoon.data.db.SearchHistoryEntry
import com.example.popcoon.data.repository.IProductRepository
import com.example.popcoon.feature.darkpattern.DarkPatternDetector
import com.example.popcoon.feature.matching.ProductMatcher
import com.example.popcoon.feature.points.PointSimulator
import com.example.popcoon.feature.scorer.BuyTimingScorer
import com.example.popcoon.feature.settings.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data object Empty : SearchUiState
    data class Results(val items: List<SearchRow>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: IProductRepository,
    private val historyDao: SearchHistoryDao,
    private val savedStateHandle: SavedStateHandle,
    private val prefs: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val currentQuery = MutableStateFlow("")

    // サジェスト: Trie 候補 + 検索履歴
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    // Trie — 検索実行した商品タイトルを蓄積してオートコンプリート
    private val trie = Trie()

    private val queryFlow = MutableStateFlow("")

    /** 進行中の検索ジョブ。新しいクエリで前の検索をキャンセルする。 */
    private var searchJob: Job? = null

    init {
        // バーコードスキャン結果受け取り
        savedStateHandle.get<String>("barcode_query")?.let { barcodeQuery ->
            if (barcodeQuery.isNotBlank()) {
                currentQuery.value = barcodeQuery
                queryFlow.value = barcodeQuery
                savedStateHandle.remove<String>("barcode_query")
            }
        }

        // 検索履歴を非同期で読み込む
        viewModelScope.launch {
            val recent = historyDao.observeRecent(10).first()
            _recentSearches.value = recent.map { it.query }
        }

        queryFlow
            .debounce(300)
            .distinctUntilChanged()
            .onEach { q ->
                updateSuggestions(q)
                searchJob?.cancel()
                searchJob = viewModelScope.launch { performSearch(q) }
            }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(q: String) {
        queryFlow.value = q
        currentQuery.value = q
    }

    private fun updateSuggestions(query: String) {
        _suggestions.value = if (query.isBlank()) emptyList()
        else trie.suggest(query, limit = 6)
    }

    private suspend fun performSearch(query: String) {
        if (query.isBlank()) {
            _state.value = SearchUiState.Idle
            return
        }
        _state.value = SearchUiState.Loading
        runCatching {
            // EC 会員設定を一度読み取って UserContext を構築 (検索中は変わらない)
            val userCtx = PointSimulator.UserContext(
                rakutenSpu = prefs.rakutenSpu.first(),
                yahooPremium = prefs.yahooPremium.first(),
                paypaySoftbank = prefs.paypaySoftbank.first(),
                amazonPrime = prefs.amazonPrime.first(),
                purchaseDate = java.time.LocalDate.now(),
            )

            val products = repository.search(query, limit = 30)
            if (products.isEmpty()) {
                _state.value = SearchUiState.Empty
                return
            }
            // 名寄せ: 同一商品をグループ化し、各グループの実質最安値を代表とする。
            // groupByIdentity は totalPrice 順だが userCtx 込みの effectivePrice で再ソート。
            // 例: 楽天 SPU8x ユーザーには楽天商品が Amazon より実質安くなり得る。
            val groups = ProductMatcher.groupByIdentity(products).map { group ->
                group.sortedBy { PointSimulator.simulate(it, userCtx).effectivePrice }
            }
            // 各グループの価格履歴取得は独立した backend 往復なので並列化する
            // (従来は逐次で、結果数ぶん直列にネットワーク待ちしていた)。
            val rows = coroutineScope {
                groups.map { group ->
                    async {
                val product = group.first()  // 最安値 (groupByIdentity がソート済み)
                val alternatives = group.drop(1)  // 他モールの同一商品
                val history = runCatching {
                    repository.getPriceHistory(product.key)
                }.onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())

                val score = BuyTimingScorer.score(
                    // realPrice matches PriceRecord.realPrice unit (sticker, no shipping).
                    // totalPrice includes shipping and would bias ATL proximity vs history.
                    current = product.realPrice,
                    listPrice = product.listPrice,
                    history = history,
                    today = java.time.LocalDate.now(),
                )
                val priceWarnings = DarkPatternDetector.detect(
                    currentPrice = product.totalPrice,
                    listPrice = product.listPrice.takeIf { it > 0 },
                    history = history,
                )
                val textWarnings = DarkPatternDetector.detectInText(product.title)
                val dripWarning = DarkPatternDetector.detectDripPricing(
                    basePrice = product.realPrice,
                    totalPrice = product.totalPrice,
                )
                val warnings = (priceWarnings + textWarnings + listOfNotNull(dripWarning))
                    .map { it.label }

                SearchRow(
                    product = product,
                    verdict = score?.verdict,
                    warnings = warnings,
                    score = score?.total ?: 0,
                    alternatives = alternatives,
                    effectivePrice = PointSimulator.simulate(product, userCtx).effectivePrice,
                )
                    }
                }.awaitAll()
            }
            // 検索履歴を保存し Trie に登録 (次回からオートコンプリートに使用)
            viewModelScope.launch {
                historyDao.insertAndDeduplicate(SearchHistoryEntry(query = query))
                _recentSearches.value = historyDao.observeRecent(10).first().map { it.query }
            }
            // 商品タイトルを Trie に追加
            products.forEach { p -> trie.insert(p.title) }

            _state.value = SearchUiState.Results(rows)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            _state.value = SearchUiState.Error(e.message?.take(80) ?: "検索失敗")
        }
    }
}
