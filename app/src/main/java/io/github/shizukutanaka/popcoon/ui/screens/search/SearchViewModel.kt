package io.github.shizukutanaka.popcoon.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shizukutanaka.popcoon.core.Trie
import io.github.shizukutanaka.popcoon.data.db.SearchHistoryDao
import io.github.shizukutanaka.popcoon.data.db.SearchHistoryEntry
import io.github.shizukutanaka.popcoon.data.db.WatchlistDao
import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.data.repository.IProductRepository
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternDetector
import io.github.shizukutanaka.popcoon.feature.matching.ProductMatcher
import io.github.shizukutanaka.popcoon.feature.points.PointSimulator
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.github.shizukutanaka.popcoon.feature.settings.IUserPreferences
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.ui.UiText
import io.github.shizukutanaka.popcoon.ui.toLabelResource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data object Empty : SearchUiState
    data class Results(val items: List<SearchRow>) : SearchUiState
    data class Error(val message: UiText) : SearchUiState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: IProductRepository,
    private val historyDao: SearchHistoryDao,
    private val savedStateHandle: SavedStateHandle,
    private val prefs: IUserPreferences,
    private val watchlistDao: WatchlistDao,
) : ViewModel() {

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    // プロセスキル → 再起動でも入力中/直前のクエリを失わないよう SavedStateHandle に復元・永続化する。
    // (バーコード結果と同じ savedStateHandle を使うが、別キーなので競合しない)
    val currentQuery = MutableStateFlow(savedStateHandle.get<String>(KEY_SEARCH_QUERY) ?: "")

    // サジェスト: Trie 候補 + 検索履歴
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    /**
     * EC 会員設定 (楽天SPU/Yahooプレミアム/PayPay/Amazonプライム) の案内バナーを出すべきか。
     * 実質価格ランキングはこれらの設定に依存するが設定画面のみに存在するため、
     * 未設定のまま気づかれずアプリの核心機能 (個人化されたポイント込み実質価格) が
     * 常に最低倍率で計算され続けるユーザーが多い。一度だけ案内し、閉じる/設定へ進む操作で
     * 二度と表示しない。
     */
    val showEcPrompt: StateFlow<Boolean> = prefs.ecPromptDismissed
        .map { dismissed -> !dismissed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismissEcPrompt() {
        viewModelScope.launch { prefs.dismissEcPrompt() }
    }

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

        // プロセス再起動での復元 (バーコード結果が優先、上のブロックで queryFlow 済みなら二重発火しない)
        if (queryFlow.value.isBlank() && currentQuery.value.isNotBlank()) {
            queryFlow.value = currentQuery.value
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
        savedStateHandle[KEY_SEARCH_QUERY] = q
    }

    /**
     * 直近のクエリで検索を再実行する (Error 状態のリトライボタン用)。
     * 同一クエリは debounce/distinctUntilChanged では再発火しないため、
     * performSearch を直接呼ぶ。空クエリのときは何もしない。
     */
    fun retry() {
        val q = currentQuery.value
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { performSearch(q) }
    }

    /**
     * 検索結果の長押しメニュー「ウォッチリストに追加」用。ProductDetailViewModel の
     * toggleWatchlist() と異なり現在の在籍状態を問わないシンプルな追加のみ (Room の
     * REPLACE 戦略により既に追加済みでも安全に冪等)。
     * 以前は ProductRow の onAddWatchlist コールバックがどこからも供給されず、
     * メニュー項目をタップしても常に無反応だった (機能過不足監査で発見)。
     */
    fun addToWatchlist(product: Product) {
        viewModelScope.launch {
            try {
                watchlistDao.upsert(
                    WatchlistItem(
                        productKey = product.key,
                        sku = product.sku,
                        title = product.title,
                        platform = product.platform.id,
                        realPrice = product.realPrice,
                        listPrice = product.listPrice,
                        url = product.url,
                        imageUrl = product.imageUrl,
                        addedPrice = product.realPrice,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.github.shizukutanaka.popcoon.core.PopcoonLogger.w(
                    this@SearchViewModel, "addToWatchlist failed: ${e.message}", e,
                )
            }
        }
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
                yahooRank = prefs.yahooRank.first(),
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
            // ただし無制限並列は禁物: backend の GET レート制限は 30回/分/IP
            // (backend/src/index.ts) で、groupByIdentity 後もグループ数は最大30近くに
            // なりうるため、1回の検索だけで制限のほぼ全てを消費しうる。PriceSyncWorker.kt
            // と同じ Semaphore(MAX_CONCURRENCY) パターンで同時実行数を絞る
            // (機能過不足監査で発見: この画面だけ無制限並列のままだった)。
            val searchSemaphore = Semaphore(MAX_CONCURRENCY)
            val rows = coroutineScope {
                groups.map { group ->
                    async { searchSemaphore.withPermit {
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
                // .label は日本語固定文字列 (BuyTimingScorer 内部識別用) なので UI 表示には使わない。
                // toLabelResource() で type/severity からロケール対応の文字列リソースへ変換する。
                // ProductRow は take(2) しか表示できないので、**切る前に深刻度順へ**。
                // 検出順のままだと [CHARM_PRICING(LOW), 在庫煽り(MEDIUM), DRIP_PRICING(HIGH)]
                // のような並びで、実害の大きい DRIP_PRICING が落ちていた。
                val warnings = DarkPatternDetector
                    .prioritize(priceWarnings + textWarnings + listOfNotNull(dripWarning))
                    .map { w ->
                        val (resId, args) = w.toLabelResource()
                        UiText.StringResource(resId, *args.toTypedArray())
                    }

                SearchRow(
                    product = product,
                    verdict = score?.verdict,
                    warnings = warnings,
                    score = score?.total ?: 0,
                    alternatives = alternatives,
                    effectivePrice = PointSimulator.simulate(product, userCtx).effectivePrice,
                )
                    } }
                }.awaitAll()
            }
            // 検索履歴を保存し Trie に登録 (次回からオートコンプリートに使用)
            viewModelScope.launch {
                historyDao.insertAndDeduplicate(SearchHistoryEntry(query = query))
                _recentSearches.value = historyDao.observeRecent(10).first().map { it.query }
            }
            // 商品タイトルを Trie に追加
            products.forEach { p -> trie.insert(p.title) }

            // LazyColumn の key = product.key が重複すると IllegalArgumentException で
            // クラッシュする。groupByIdentity はタイトル類似性で束ねるため、同一 platform:sku が
            // 別グループに分かれると同じ key の行が 2 つ生じうる (API の重複・ページ重複等)。
            // 表示直前に key で一意化し、クラッシュを構造的に防ぐ (最安値=先頭を保持)。
            _state.value = SearchUiState.Results(rows.distinctBy { it.product.key })
        }.onFailure { e ->
            if (e is CancellationException) throw e
            // 生の例外メッセージ (英語スタックトレース等) をユーザーに見せない。
            // ネットワーク起因は専用の案内文、それ以外は汎用エラーに丸める。
            val isNetworkError = e is java.io.IOException
            _state.value = SearchUiState.Error(
                UiText.StringResource(
                    if (isNetworkError) R.string.error_network_unavailable
                    else R.string.error_search_failed,
                ),
            )
        }
    }

    private companion object {
        const val KEY_SEARCH_QUERY = "search_query"
        // PriceSyncWorker.kt と同じ上限。backend の GET レート制限 (30回/分/IP) を
        // 1回の検索だけで消費し尽くさないための同時実行数キャップ。
        const val MAX_CONCURRENCY = 8
    }
}
