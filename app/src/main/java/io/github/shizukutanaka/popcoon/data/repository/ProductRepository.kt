package io.github.shizukutanaka.popcoon.data.repository

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.data.network.AmazonPaApiClient
import io.github.shizukutanaka.popcoon.data.network.FallbackScraper
import io.github.shizukutanaka.popcoon.data.network.RakutenClient
import io.github.shizukutanaka.popcoon.data.network.YahooClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import io.github.shizukutanaka.popcoon.core.PopcoonLogger

/**
 * 3EC 横断検索の統合リポジトリ。
 *
 * 責務:
 *  - 3プラットフォームに並列問い合わせ
 *  - タイムアウト (1プラットフォーム 5秒) で遅い API を遮断
 *  - エラー時は他プラットフォーム結果を返す
 *  - 価格履歴を backend (Cloudflare Workers) に追記
 */
/**
 * ProductRepository のインターフェース。
 *
 * テスト時に FakeProductRepository で差し替え可能にする。
 * (Robert C. Martin: 依存性逆転の原則)
 *
 * スコープ (@Singleton) は NetworkModule.provideProductRepository 側で付与する。
 * インターフェースに @Singleton を付けても無意味なため置かない。
 */
interface IProductRepository {
    suspend fun search(keyword: String, limit: Int = 10): List<Product>
    suspend fun refresh(product: Product): Product?
    suspend fun getPriceHistory(productKey: String): List<PriceRecord>
}

/**
 * 3 プラットフォーム全てが失敗 (例外 or サーキットブレーカー OPEN) した場合に投げる。
 *
 * 以前は searchWithBreaker が全ての失敗を emptyList() に握りつぶしていたため、
 * ネットワーク全断時でも SearchViewModel は「該当商品なし」(Empty) と区別が
 * つかず、リトライ導線も出せなかった (商用リリース監査で発見)。
 * IOException を継承することで SearchViewModel の既存の
 * `e is java.io.IOException` 分岐 (ネットワークエラー用の案内文) にそのまま乗る。
 *
 * 「一部の情報源は成功したが 0 件だった」場合はこの例外を投げない —
 * その場合は Empty (該当商品なし) が正しい表現のまま。
 */
class AllSourcesUnavailableException : java.io.IOException("All product sources failed or are circuit-open")

open class ProductRepository @Inject constructor(
    private val amazon: AmazonPaApiClient,
    private val rakuten: RakutenClient,
    private val yahoo: YahooClient,
    private val fallback: FallbackScraper,
    private val backend: BackendClient,
) : IProductRepository {

    // プラットフォーム毎に独立したブレーカー。1 つの API が連続障害中でも
    // 他 2 プラットフォームの検索速度には影響しない。
    private val amazonBreaker = CircuitBreaker()
    private val rakutenBreaker = CircuitBreaker()
    private val yahooBreaker = CircuitBreaker()

    /**
     * 3 EC 並列検索。1つが失敗しても他の結果を返す。
     * 3つ全てが失敗した場合のみ [AllSourcesUnavailableException] を投げ、
     * 呼び出し側 (SearchViewModel) がネットワーク全断とジャンル該当なしを区別できるようにする。
     * fire-and-forget で backend に価格を送信 (ユーザー体感を阻害しない)。
     */
    override suspend fun search(keyword: String, limit: Int = 10): List<Product> = coroutineScope {
        val amazonJob = async {
            searchWithBreaker("Amazon", amazonBreaker) { amazon.searchItems(keyword, limit) }
        }
        val rakutenJob = async {
            searchWithBreaker("楽天", rakutenBreaker) { rakuten.search(keyword, limit) }
        }
        val yahooJob = async {
            searchWithBreaker("Yahoo", yahooBreaker) { yahoo.search(keyword, limit) }
        }

        val outcomes = listOf(amazonJob.await(), rakutenJob.await(), yahooJob.await())
        val results = outcomes.flatMap { it.products }

        if (results.isEmpty() && outcomes.all { it.failed }) {
            throw AllSourcesUnavailableException()
        }

        // 非同期で backend に価格履歴をまとめて投稿 (UI をブロックしない、1 コルーチン)
        backend.postPricesAsync(
            results.map { product ->
                PriceRecord(
                    productKey = product.key,
                    platform = product.platform.id,
                    listPrice = product.listPrice,
                    realPrice = product.realPrice,
                    recordedAt = java.time.Instant.now(),
                )
            },
        )

        // 安い順にソート (送料込み実質合計)
        results.sortedBy { it.totalPrice }
    }

    /**
     * 商品キーから最新情報を再取得。
     * API 失敗時は FallbackScraper で商品ページから JSON-LD を解析。
     *
     * 失敗時 (URL 空・例外・JSON-LD 解析失敗) は null を返す — **元の product にフォール
     * バックしてはならない**。以前は `?: product` で失敗を握りつぶし、呼び出し元に
     * 「取得できたが変化なし」であるかのような偽装データ (実際は未更新の古い product)
     * を返していた。これが PriceSyncWorker の在庫アラート判定 (StockAlertEvaluator) と
     * 組み合わさると、フォールバック取得が単に失敗しただけ (ネットワークエラー・
     * robots.txt 拒否・ページ構造変更等) のケースで「在庫あり」の確定情報として扱われ、
     * 在庫切れ→在庫復活の誤検知プッシュ通知を大量発生させていた (機能過不足監査で発見)。
     * 呼び出し元は元々 null を正しく処理する設計だった (PriceSyncWorker.kt:199 の
     * `?: return@runCatching` は本来失敗時にスキップするためのガードだったが、この関数が
     * 常に非 null を返すため到達しない死コードになっていた。ProductDetailViewModel.kt の
     * `refreshDeferred?.await() ?: cachedProduct ?: buildProductFromKey(...)` も同様に
     * null を想定したフォールバックチェーンを既に持つ)。
     */
    override suspend fun refresh(product: Product): Product? {
        val url = product.url.ifEmpty { return null }
        return try {
            fallback.fetchProduct(url, product.platform)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PopcoonLogger.w("ProductRepository", "詳細フォールバック取得失敗", e)
            null
        }
    }

    /** 商品の価格履歴を backend から取得 */
    override suspend fun getPriceHistory(productKey: String): List<PriceRecord> {
        return try { backend.getPriceHistory(productKey) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            PopcoonLogger.w("ProductRepository", "価格履歴取得失敗", e)
            emptyList()
        }
    }

    /**
     * サーキットブレーカー越しに 1 プラットフォームを検索する共通処理。
     * OPEN 中は API を呼ばず即座に空リストを返す (タイムアウト待ちを回避)。
     *
     * `failed = true` は「この情報源から有効な回答を得られなかった」(例外 or
     * ブレーカー OPEN) ことを示し、`search()` が「全滅かどうか」を判定するのに使う。
     * API 呼び出し自体が成功して 0 件だった場合は `failed = false` のまま
     * (それは正当な「該当なし」であり、全滅判定に含めるべきではない)。
     */
    private suspend fun searchWithBreaker(
        label: String,
        breaker: CircuitBreaker,
        call: suspend () -> List<Product>,
    ): SourceOutcome {
        val now = System.currentTimeMillis()
        if (!breaker.allowRequest(now)) {
            PopcoonLogger.w("ProductRepository", "$label はサーキットブレーカー OPEN 中 — スキップ")
            return SourceOutcome(emptyList(), failed = true)
        }
        return try {
            val products = call()
            breaker.recordSuccess()
            SourceOutcome(products, failed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            breaker.recordFailure(System.currentTimeMillis())
            PopcoonLogger.w("ProductRepository", "$label 検索失敗", e)
            SourceOutcome(emptyList(), failed = true)
        }
    }

    private data class SourceOutcome(val products: List<Product>, val failed: Boolean)
}
