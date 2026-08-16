package io.github.shizukutanaka.popcoon.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shizukutanaka.popcoon.data.db.WatchlistDao
import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import io.github.shizukutanaka.popcoon.feature.cart.SmartCartService
import io.github.shizukutanaka.popcoon.feature.points.PointSimulator
import io.github.shizukutanaka.popcoon.feature.settings.IUserPreferences
import io.github.shizukutanaka.popcoon.feature.watchlist.WatchlistSort
import io.github.shizukutanaka.popcoon.widget.IWidgetRefresher
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: WatchlistDao,
    private val prefs: IUserPreferences,
    private val widgetRefresher: IWidgetRefresher,
) : ViewModel() {

    // Room の observe クエリはテーブルへの任意の書き込みで再発火する (結果が同一でも)。
    // PriceSyncWorker が毎日 lastNotifiedPrice/在庫を更新するため、表示リストが変わらなくても
    // 下流の WatchlistSort.sort と SmartCartService.optimize (最大 200k 通り) が無駄に再計算される。
    // distinctUntilChanged で同一内容の List を構造等価で弾き、再計算を抑制する。
    private val rawItems: Flow<List<WatchlistItem>> = dao.observeAll().distinctUntilChanged()

    /** 現在の並べ替えモード（永続化された設定から復元）。 */
    // 冷たい Flow を Compose に直接公開すると、購読のたびに upstream (DataStore 読込) が
    // 再実行される。stateIn(WhileSubscribed) で StateFlow 化し、画面回転やナビ往復の短い
    // 非購読を跨いで値を保持し、再計算を避ける (smartCart と同方針)。
    val sortMode: StateFlow<WatchlistSort.Mode> = prefs.watchlistSortOrdinal
        .map { ordinal ->
            WatchlistSort.Mode.entries.getOrElse(ordinal) { WatchlistSort.Mode.ADDED_DESC }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchlistSort.Mode.ADDED_DESC)

    /**
     * 並べ替え適用済みのウォッチリスト。画面はこれを購読する。
     *
     * こちらは smartCart よりも影響が大きい: WatchlistSort.sort が万一例外を投げると
     * stateIn(WhileSubscribed) は例外終了から自動復帰しないため、画面の主表示そのものが
     * 古いリストのまま固まってしまう (アイテム追加・削除・並べ替えが一切反映されなくなる)。
     * catch でフォールバックし、少なくとも未整列の raw list を表示し続ける。
     */
    val items: StateFlow<List<WatchlistItem>> = combine(rawItems, sortMode) { list, mode ->
        WatchlistSort.sort(list, mode)
    }
        .catch { e ->
            if (e is CancellationException) throw e
            PopcoonLogger.w(this@WatchlistViewModel, "ウォッチリスト並べ替えに失敗: ${e.message}", e)
            emit(rawItems.first())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 現在使用中のタグ一覧 (フィルタチップ表示用)。
     * (機能過不足監査 B4: ウォッチリストのタグ/フォルダ分類が無かった、への対応)
     */
    val availableTags: StateFlow<List<String>> = dao.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 選択中のタグフィルタ。null = 「すべて」(フィルタなし)。永続化はせず画面単位の一時状態。 */
    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag

    /** タグフィルタ適用後のウォッチリスト。画面はこれを購読する (未選択時は items と同一)。 */
    val filteredItems: StateFlow<List<WatchlistItem>> = combine(items, _selectedTag) { list, tag ->
        if (tag == null) list else list.filter { it.tag == tag }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectTagFilter(tag: String?) {
        _selectedTag.value = tag
    }

    /** タグ (フォルダ分類) を設定 / 解除する。@param tag null または空文字で「未分類」に戻す。 */
    fun setTag(productKey: String, tag: String?) {
        viewModelScope.launch {
            try {
                dao.setTag(productKey, tag?.takeIf { it.isNotBlank() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@WatchlistViewModel, "setTag failed: ${e.message}", e)
            }
        }
    }

    /**
     * ウォッチリスト全体の横断カート最適化結果。
     * 2件以上あれば自動計算。最適化は総当たり (最大 200k 通り) になり得るため
     * Dispatchers.Default に逃がし、メインスレッドをブロックしない。
     * 並べ替え順は最適化結果に影響しないため raw を使う。
     * EC 会員設定 (UserPreferences) から UserContext を構築して PointSimulator に供給。
     */
    // EC 会員設定は 5 つを超えるため、型付き combine の上限 (5 引数) に合わせて
    // 「UserContext の組み立て」と「rawItems との結合」を 2 段に分ける。
    private val userContext = combine(
        prefs.rakutenSpu,
        prefs.yahooPremium,
        prefs.paypaySoftbank,
        prefs.amazonPrime,
        prefs.yahooRank,
    ) { spu, yp, sb, ap, rank ->
        PointSimulator.UserContext(
            rakutenSpu = spu,
            yahooPremium = yp,
            paypaySoftbank = sb,
            amazonPrime = ap,
            yahooRank = rank,
        )
    }

    val smartCart = combine(rawItems, userContext) { list, userCtx ->
        if (list.size < 2) return@combine null
        SmartCartService.optimize(list, userCtx = userCtx)
    }
        .flowOn(Dispatchers.Default)
        // stateIn(WhileSubscribed) は「未処理の例外による Flow の終了」からは自動復帰しない
        // (start/stop の再購読でしか upstream を再起動しないため)。catch を入れないと、
        // optimize() が一度でも例外を投げた時点でこの StateFlow は以後ずっと更新が止まる
        // (エラー表示もされず、ただ古い値のまま固まって見える)。
        .catch { e ->
            if (e is CancellationException) throw e
            PopcoonLogger.w(this@WatchlistViewModel, "smartCart 最適化に失敗: ${e.message}", e)
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSortMode(mode: WatchlistSort.Mode) {
        viewModelScope.launch { prefs.setWatchlistSort(mode.ordinal) }
    }

    fun remove(productKey: String) {
        viewModelScope.launch {
            try {
                dao.delete(productKey)
                updateWidget()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@WatchlistViewModel, "remove failed: ${e.message}", e)
            }
        }
    }

    fun add(item: WatchlistItem) {
        viewModelScope.launch {
            try {
                dao.upsert(item)
                updateWidget()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@WatchlistViewModel, "add failed: ${e.message}", e)
            }
        }
    }

    /**
     * 目標価格を設定 / 解除する。
     * 次回の価格同期で、この価格以下になったら値下がり率に関係なく通知される。
     * @param target null で解除。
     */
    fun setTargetPrice(productKey: String, target: Long?) {
        viewModelScope.launch {
            try {
                dao.setTargetPrice(productKey, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@WatchlistViewModel, "setTargetPrice failed: ${e.message}", e)
            }
        }
    }

    /**
     * 在庫アラートの on/off を切り替える。
     * ON にすると PriceSyncWorker が毎日ライブ在庫を確認し、
     * 品切れ→在庫あり に変化したタイミングで通知する。
     */
    fun setStockAlertEnabled(productKey: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                dao.setStockAlertEnabled(productKey, enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@WatchlistViewModel, "setStockAlertEnabled failed: ${e.message}", e)
            }
        }
    }

    private suspend fun updateWidget() {
        val current = rawItems.first()
        widgetRefresher.refresh(current)
    }
}
