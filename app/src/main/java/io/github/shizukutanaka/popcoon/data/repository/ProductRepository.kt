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

        val results = listOf(amazonJob.await(), rakutenJob.await(), yahooJob.await())
            .flatten()

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
        } ?: product
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
     */
    private suspend fun searchWithBreaker(
        label: String,
        breaker: CircuitBreaker,
        call: suspend () -> List<Product>,
    ): List<Product> {
        val now = System.currentTimeMillis()
        if (!breaker.allowRequest(now)) {
            PopcoonLogger.w("ProductRepository", "$label はサーキットブレーカー OPEN 中 — スキップ")
            return emptyList()
        }
        return try {
            call().also { breaker.recordSuccess() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            breaker.recordFailure(System.currentTimeMillis())
            PopcoonLogger.w("ProductRepository", "$label 検索失敗", e)
            emptyList()
        }
    }
}
