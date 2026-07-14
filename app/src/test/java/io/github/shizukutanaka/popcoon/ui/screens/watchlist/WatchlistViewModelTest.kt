package io.github.shizukutanaka.popcoon.ui.screens.watchlist

import io.github.shizukutanaka.popcoon.data.db.WatchlistDao
import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import io.github.shizukutanaka.popcoon.feature.settings.IUserPreferences
import io.github.shizukutanaka.popcoon.feature.watchlist.WatchlistSort
import io.github.shizukutanaka.popcoon.widget.IWidgetRefresher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private fun item(key: String, price: Long = 1000, tag: String? = null) = WatchlistItem(
    productKey = key, sku = key, title = "商品-$key", platform = "AMAZON",
    realPrice = price, listPrice = price, url = "https://example.com/$key", imageUrl = null,
    tag = tag,
)

private fun makeViewModel(
    dao: WatchlistDao = FakeWatchlistDao(),
    prefs: IUserPreferences = FakeUserPreferences(),
    widgetRefresher: IWidgetRefresher = FakeWidgetRefresher(),
): WatchlistViewModel = WatchlistViewModel(dao, prefs, widgetRefresher)

@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistViewModelTest : StringSpec({

    val testDispatcher = StandardTestDispatcher()

    beforeTest { Dispatchers.setMain(testDispatcher) }
    afterTest { Dispatchers.resetMain() }

    "remove がアイテムを削除する" {
        runTest(testDispatcher) {
            val dao = FakeWatchlistDao().apply { items["k1"] = item("k1") }
            val vm = makeViewModel(dao = dao)
            vm.remove("k1")
            advanceUntilIdle()
            dao.items.containsKey("k1") shouldBe false
        }
    }

    "remove が DAO 例外を投げても vm はクラッシュせずアイテムは残る" {
        runTest(testDispatcher) {
            val dao = FakeWatchlistDao(throwOn = mutableSetOf("delete")).apply {
                items["k1"] = item("k1")
            }
            val vm = makeViewModel(dao = dao)
            vm.remove("k1")
            advanceUntilIdle()
            dao.items.containsKey("k1") shouldBe true
        }
    }

    "add がアイテムを追加する" {
        runTest(testDispatcher) {
            val dao = FakeWatchlistDao()
            val vm = makeViewModel(dao = dao)
            vm.add(item("k2"))
            advanceUntilIdle()
            dao.items.containsKey("k2") shouldBe true
        }
    }

    "add が DAO 例外を投げても vm はクラッシュしない" {
        runTest(testDispatcher) {
            val dao = FakeWatchlistDao(throwOn = mutableSetOf("upsert"))
            val vm = makeViewModel(dao = dao)
            vm.add(item("k3"))
            advanceUntilIdle()
            dao.items.containsKey("k3") shouldBe false
        }
    }

    "setTag が DAO 例外を投げても vm はクラッシュしない" {
        runTest(testDispatcher) {
            val dao = FakeWatchlistDao(throwOn = mutableSetOf("setTag")).apply {
                items["k4"] = item("k4")
            }
            val vm = makeViewModel(dao = dao)
            vm.setTag("k4", "電化製品")
            advanceUntilIdle()
            dao.items["k4"]?.tag shouldBe null
        }
    }

    "setTag が正常時はタグを更新する" {
        runTest(testDispatcher) {
            val dao = FakeWatchlistDao().apply { items["k5"] = item("k5") }
            val vm = makeViewModel(dao = dao)
            vm.setTag("k5", "電化製品")
            advanceUntilIdle()
            dao.items["k5"]?.tag shouldBe "電化製品"
        }
    }

    "setTargetPrice が DAO 例外を投げても vm はクラッシュしない" {
        runTest(testDispatcher) {
            val dao = FakeWatchlistDao(throwOn = mutableSetOf("setTargetPrice")).apply {
                items["k6"] = item("k6")
            }
            val vm = makeViewModel(dao = dao)
            vm.setTargetPrice("k6", 500L)
            advanceUntilIdle()
            dao.items["k6"]?.targetPrice shouldBe null
        }
    }

    "setStockAlertEnabled が DAO 例外を投げても vm はクラッシュしない" {
        runTest(testDispatcher) {
            val dao = FakeWatchlistDao(throwOn = mutableSetOf("setStockAlertEnabled")).apply {
                items["k7"] = item("k7")
            }
            val vm = makeViewModel(dao = dao)
            vm.setStockAlertEnabled("k7", true)
            advanceUntilIdle()
            dao.items["k7"]?.stockAlertEnabled shouldBe false
        }
    }

    "setSortMode で sortMode が反映される" {
        runTest(testDispatcher) {
            val prefs = FakeUserPreferences()
            val vm = makeViewModel(prefs = prefs)
            // sortMode は stateIn(WhileSubscribed) 経由のため、購読者が居ないと upstream
            // (DataStore フェイク) が動かず .value が初期値のまま固まる。明示的に購読して起動する。
            val job = launch { vm.sortMode.collect {} }
            advanceUntilIdle()
            vm.sortMode.value shouldBe WatchlistSort.Mode.ADDED_DESC

            vm.setSortMode(WatchlistSort.Mode.PRICE_ASC)
            advanceUntilIdle()
            vm.sortMode.value shouldBe WatchlistSort.Mode.PRICE_ASC
            job.cancel()
        }
    }

    "selectTagFilter で filteredItems が絞り込まれる" {
        runTest(testDispatcher) {
            val dao = FakeWatchlistDao().apply {
                items["a"] = item("a", tag = "本")
                items["b"] = item("b", tag = "家電")
            }
            val vm = makeViewModel(dao = dao)
            // filteredItems は items→sortMode→rawItems と連鎖する stateIn(WhileSubscribed) の
            // 末端。ここを購読すれば連鎖全体が起動する。
            val job = launch { vm.filteredItems.collect {} }
            advanceUntilIdle()
            vm.filteredItems.value.size shouldBe 2

            vm.selectTagFilter("本")
            advanceUntilIdle()
            vm.filteredItems.value.size shouldBe 1
            vm.filteredItems.value.first().productKey shouldBe "a"
            job.cancel()
        }
    }
})

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeUserPreferences(
    spu: Int = 1,
    yahoo: Boolean = false,
    softbank: Boolean = false,
    prime: Boolean = false,
) : IUserPreferences {
    override val rakutenSpu: Flow<Int> = flowOf(spu)
    override val yahooPremium: Flow<Boolean> = flowOf(yahoo)
    override val paypaySoftbank: Flow<Boolean> = flowOf(softbank)
    override val amazonPrime: Flow<Boolean> = flowOf(prime)
    override val ecPromptDismissed: Flow<Boolean> = flowOf(true)
    override suspend fun dismissEcPrompt() {}

    private val sortOrdinal = MutableStateFlow(0)
    override val watchlistSortOrdinal: Flow<Int> = sortOrdinal
    override suspend fun setWatchlistSort(ordinal: Int) {
        sortOrdinal.value = ordinal
    }
}

private class FakeWidgetRefresher : IWidgetRefresher {
    val refreshedWith = mutableListOf<List<WatchlistItem>>()
    override fun refresh(items: List<WatchlistItem>) {
        refreshedWith += items
    }
}

/** DAO 呼び出しを模倣するフェイク。[throwOn] に操作名を入れると呼び出し時に例外を投げる (try/catch 回帰用)。 */
private class FakeWatchlistDao(
    private val throwOn: MutableSet<String> = mutableSetOf(),
) : WatchlistDao {
    val items = mutableMapOf<String, WatchlistItem>()
    private val flow = MutableStateFlow<List<WatchlistItem>>(emptyList())

    private fun publish() { flow.value = items.values.sortedByDescending { it.addedAt } }
    private fun maybeThrow(op: String) {
        if (op in throwOn) throw RuntimeException("fake DAO failure: $op")
    }

    override fun observeAll(): Flow<List<WatchlistItem>> = flow
    override suspend fun get(key: String) = items[key]
    override suspend fun upsert(item: WatchlistItem) {
        maybeThrow("upsert")
        items[item.productKey] = item
        publish()
    }
    override suspend fun delete(key: String) {
        maybeThrow("delete")
        items.remove(key)
        publish()
    }
    override suspend fun setTargetPrice(key: String, target: Long?) {
        maybeThrow("setTargetPrice")
        items[key]?.let { items[key] = it.copy(targetPrice = target) }
        publish()
    }
    override suspend fun updatePrice(key: String, price: Long) {
        items[key]?.let { items[key] = it.copy(realPrice = price) }
        publish()
    }
    override suspend fun updatePriceAndPending(key: String, price: Long, pendingPrice: Long?) {
        items[key]?.let { items[key] = it.copy(realPrice = price, pendingPrice = pendingPrice) }
        publish()
    }
    override suspend fun setStockAlertEnabled(key: String, enabled: Boolean) {
        maybeThrow("setStockAlertEnabled")
        items[key]?.let { items[key] = it.copy(stockAlertEnabled = enabled) }
        publish()
    }
    override suspend fun updateStockState(key: String, wasInStock: Boolean) {
        items[key]?.let { items[key] = it.copy(previousInStock = wasInStock) }
        publish()
    }
    override suspend fun setTag(key: String, tag: String?) {
        maybeThrow("setTag")
        items[key]?.let { items[key] = it.copy(tag = tag) }
        publish()
    }
    override fun observeTags(): Flow<List<String>> =
        MutableStateFlow(items.values.mapNotNull { it.tag }.distinct().sorted())
    override suspend fun count(): Int = items.size
    override suspend fun deleteAll() { items.clear(); publish() }
}
