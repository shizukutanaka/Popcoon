package io.github.shizukutanaka.popcoon.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import io.github.shizukutanaka.popcoon.data.db.SearchHistoryDao
import io.github.shizukutanaka.popcoon.data.db.SearchHistoryEntry
import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.data.repository.IProductRepository
import io.github.shizukutanaka.popcoon.feature.settings.IUserPreferences
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Instant

private fun makeViewModel(
    repo: IProductRepository = FakeRepository(),
    historyDao: SearchHistoryDao = FakeSearchHistoryDao(),
    barcodeQuery: String? = null,
    prefs: IUserPreferences = FakeUserPreferences(),
): SearchViewModel {
    val state = SavedStateHandle(
        buildMap { barcodeQuery?.let { put("barcode_query", it) } }
    )
    return SearchViewModel(repo, historyDao, state, prefs)
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest : StringSpec({

    val testDispatcher = StandardTestDispatcher()

    beforeTest { Dispatchers.setMain(testDispatcher) }
    afterTest { Dispatchers.resetMain() }

    "初期状態は Idle" {
        val vm = makeViewModel()
        vm.state.value.shouldBeInstanceOf<SearchUiState.Idle>()
    }

    "空文字入力でも Idle のまま" {
        runTest(testDispatcher) {
            val vm = makeViewModel()
            vm.onQueryChange("")
            advanceTimeBy(500)
            vm.state.value.shouldBeInstanceOf<SearchUiState.Idle>()
        }
    }

    "300ms debounce 後に検索が走る" {
        runTest(testDispatcher) {
            val repo = FakeRepository(products = listOf(
                Product("X1", "テスト商品", Platform.AMAZON, 1000, 1500),
            ))
            val vm = makeViewModel(repo = repo)

            vm.onQueryChange("テスト")
            advanceTimeBy(200)
            vm.state.value.shouldBeInstanceOf<SearchUiState.Idle>()

            advanceTimeBy(200)
            vm.state.value.shouldBeInstanceOf<SearchUiState.Results>()
        }
    }

    "結果なしで Empty" {
        runTest(testDispatcher) {
            val vm = makeViewModel(repo = FakeRepository(products = emptyList()))
            vm.onQueryChange("見つからない")
            advanceTimeBy(500)
            vm.state.value.shouldBeInstanceOf<SearchUiState.Empty>()
        }
    }

    // ProductRepository.search() は 3 プラットフォーム全滅時のみ IOException
    // (AllSourcesUnavailableException) を投げるよう変更された (以前は emptyList() に
    // 握りつぶし、ネットワーク全断でも「該当商品なし」と誤表示していた)。
    // この分岐 (IOException → Error) 自体は元から実装済みだったが、ProductRepository が
    // 一度も例外を投げなかったため到達不能だった — その到達可能性をここで固定する。
    "リポジトリが IOException を投げると Empty ではなく Error になる (全ソース障害)" {
        runTest(testDispatcher) {
            val vm = makeViewModel(repo = ThrowingRepository(java.io.IOException("all sources down")))
            vm.onQueryChange("何か")
            advanceTimeBy(500)
            vm.state.value.shouldBeInstanceOf<SearchUiState.Error>()
        }
    }

    "IOException 以外の例外も Error になる (ネットワーク以外の障害)" {
        runTest(testDispatcher) {
            val vm = makeViewModel(repo = ThrowingRepository(RuntimeException("unexpected")))
            vm.onQueryChange("何か")
            advanceTimeBy(500)
            vm.state.value.shouldBeInstanceOf<SearchUiState.Error>()
        }
    }

    "連続入力は最後だけ反映 (distinctUntilChanged + debounce)" {
        runTest(testDispatcher) {
            val repo = CountingRepository()
            val vm = makeViewModel(repo = repo)

            vm.onQueryChange("a")
            advanceTimeBy(50)
            vm.onQueryChange("ab")
            advanceTimeBy(50)
            vm.onQueryChange("abc")
            advanceTimeBy(500)

            repo.searchCount shouldBe 1
        }
    }

    "barcode_query が SavedStateHandle から注入される" {
        runTest(testDispatcher) {
            val vm = makeViewModel(barcodeQuery = "4901681528707")
            vm.currentQuery.value shouldBe "4901681528707"
        }
    }

    "barcode_query 注入後に検索が自動実行される" {
        runTest(testDispatcher) {
            val repo = FakeRepository(products = listOf(
                Product("B1", "JAN商品", Platform.AMAZON, 2000, 3000),
            ))
            val vm = makeViewModel(repo = repo, barcodeQuery = "4901681528707")
            advanceTimeBy(500)
            vm.state.value.shouldBeInstanceOf<SearchUiState.Results>()
        }
    }

    "検索成功後にサジェストが蓄積される" {
        runTest(testDispatcher) {
            val repo = FakeRepository(products = listOf(
                Product("P1", "ハーゲンダッツ バニラ", Platform.AMAZON, 300, 350),
            ))
            val vm = makeViewModel(repo = repo)
            vm.onQueryChange("ハーゲン")
            advanceTimeBy(500)
            // Trie に商品タイトルが登録されたはず → 同一クエリで具体的な候補が返る
            vm.onQueryChange("ハーゲン")
            advanceTimeBy(100)
            // isNotEmpty() では「何かある」しか検証できない。実際に挿入したタイトルを確認。
            vm.suggestions.value shouldContain "ハーゲンダッツ バニラ"
        }
    }
    "retry() は直近クエリで再検索する (Error からの復帰)" {
        runTest(testDispatcher) {
            // 1回目: 結果あり → Results。retry 後も結果が返ることを確認。
            val repo = FakeRepository(products = listOf(
                Product("R1", "リトライ商品", Platform.AMAZON, 1000, 1200),
            ))
            val vm = makeViewModel(repo = repo)
            vm.onQueryChange("リトライ")
            advanceTimeBy(500)
            vm.state.value.shouldBeInstanceOf<SearchUiState.Results>()

            vm.retry()
            advanceTimeBy(500)
            vm.state.value.shouldBeInstanceOf<SearchUiState.Results>()
        }
    }

    "retry() は空クエリでは何もしない" {
        runTest(testDispatcher) {
            val repo = CountingRepository()
            val vm = makeViewModel(repo = repo)
            vm.retry()
            advanceTimeBy(500)
            repo.searchCount shouldBe 0
        }
    }

    "検索結果は product.key で一意化される (LazyColumn 重複キークラッシュ防止)" {
        runTest(testDispatcher) {
            // 同一 platform:sku だがタイトルが異なる 2 件。groupByIdentity が別グループに
            // 分ければ同じ product.key の行が 2 つ生じ、LazyColumn が IllegalArgumentException
            // でクラッシュしうる。distinctBy { product.key } で防げていることを保証する。
            val dup = listOf(
                Product("SAME", "コーヒー豆 ブラジル産 500g", Platform.AMAZON, 1000, 1200),
                Product("SAME", "緑茶 静岡 100袋入り", Platform.AMAZON, 1100, 1300),
            )
            val vm = makeViewModel(repo = FakeRepository(products = dup))
            vm.onQueryChange("テスト")
            advanceTimeBy(500)
            val results = vm.state.value.shouldBeInstanceOf<SearchUiState.Results>()
            val keys = results.items.map { it.product.key }
            keys shouldBe keys.distinct()  // 重複キーが残っていないこと
        }
    }
})

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeUserPreferences(
    spu: Int = 1,
    yahoo: Boolean = false,
    softbank: Boolean = false,
    prime: Boolean = false,
    ecPromptDismissed: Boolean = true,
) : IUserPreferences {
    override val rakutenSpu: Flow<Int> = flowOf(spu)
    override val yahooPremium: Flow<Boolean> = flowOf(yahoo)
    override val paypaySoftbank: Flow<Boolean> = flowOf(softbank)
    override val amazonPrime: Flow<Boolean> = flowOf(prime)
    override val ecPromptDismissed: Flow<Boolean> = flowOf(ecPromptDismissed)
    override suspend fun dismissEcPrompt() {}
}

/**
 * テスト用の ProductRepository。
 * コンストラクタ引数の error() は lazy eval されないため
 * デフォルト引数で null を渡し、Hilt 以外のコンテキストでも安全に使う。
 */
private class FakeRepository(
    private val products: List<Product> = emptyList(),
) : io.github.shizukutanaka.popcoon.data.repository.IProductRepository {
    override suspend fun search(keyword: String, limit: Int): List<Product> = products
    override suspend fun refresh(product: Product): Product? = null
    override suspend fun getPriceHistory(productKey: String): List<PriceRecord> = emptyList()
}

/** search() が常に指定した例外を投げるリポジトリ。全ソース障害系のテスト用。 */
private class ThrowingRepository(
    private val exception: Throwable,
) : io.github.shizukutanaka.popcoon.data.repository.IProductRepository {
    override suspend fun search(keyword: String, limit: Int): List<Product> = throw exception
    override suspend fun refresh(product: Product): Product? = null
    override suspend fun getPriceHistory(productKey: String): List<PriceRecord> = emptyList()
}

private class CountingRepository : io.github.shizukutanaka.popcoon.data.repository.IProductRepository {
    var searchCount = 0
    override suspend fun search(keyword: String, limit: Int): List<Product> {
        searchCount++
        return emptyList()
    }
    override suspend fun refresh(product: Product): Product? = null
    override suspend fun getPriceHistory(productKey: String): List<PriceRecord> = emptyList()
}

private class FakeSearchHistoryDao : SearchHistoryDao {
    private val entries = mutableListOf<SearchHistoryEntry>()

    override fun observeRecent(limit: Int): Flow<List<SearchHistoryEntry>> =
        MutableStateFlow(entries.takeLast(limit))

    override suspend fun insert(entry: SearchHistoryEntry) {
        entries += entry
    }

    override suspend fun deduplicate(q: String) {
        val grouped = entries.filter { it.query == q }
        if (grouped.size > 1) {
            entries.removeAll(grouped.dropLast(1))
        }
    }

    override suspend fun trim(keep: Int) {
        if (entries.size > keep) {
            val excess = entries.size - keep
            repeat(excess) { entries.removeFirst() }
        }
    }

    override suspend fun deleteAll() { entries.clear() }
}
