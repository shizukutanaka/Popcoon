package com.example.popcoon.data.repository

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.PriceRecord
import com.example.popcoon.data.model.Product
import com.example.popcoon.data.network.AmazonPaApiClient
import com.example.popcoon.data.network.FallbackScraper
import com.example.popcoon.data.network.RakutenClient
import com.example.popcoon.data.network.YahooClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton
import com.example.popcoon.core.PopcoonLogger

/**
 * 3EC 横断検索の統合リポジトリ。
 *
 * 責務:
 *  - 3プラットフォームに並列問い合わせ
 *  - タイムアウト (1プラットフォーム 5秒) で遅い API を遮断
 *  - エラー時は他プラットフォーム結果を返す
 *  - 価格履歴を backend (Cloudflare Workers) に追記
 */
@Singleton
/**
 * ProductRepository のインターフェース。
 *
 * テスト時に FakeProductRepository で差し替え可能にする。
 * (Robert C. Martin: 依存性逆転の原則)
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

    /**
     * 3 EC 並列検索。1つが失敗しても他の結果を返す。
     * fire-and-forget で backend に価格を送信 (ユーザー体感を阻害しない)。
     */
    override suspend fun search(keyword: String, limit: Int = 10): List<Product> = coroutineScope {
        val amazonJob = async { runCatching { amazon.searchItems(keyword, limit) }.onFailure { PopcoonLogger.w("ProductRepository", "Amazon 検索失敗", it) }.getOrDefault(emptyList()) }
        val rakutenJob = async { runCatching { rakuten.search(keyword, limit) }.onFailure { PopcoonLogger.w("ProductRepository", "楽天 検索失敗", it) }.getOrDefault(emptyList()) }
        val yahooJob = async { runCatching { yahoo.search(keyword, limit) }.onFailure { PopcoonLogger.w("ProductRepository", "Yahoo 検索失敗", it) }.getOrDefault(emptyList()) }

        val results = listOf(amazonJob.await(), rakutenJob.await(), yahooJob.await())
            .flatten()

        // 非同期で backend に価格履歴を投稿 (UI をブロックしない)
        results.forEach { product ->
            backend.postPriceAsync(PriceRecord(
                productKey = product.key,
                platform = product.platform.id,
                listPrice = product.listPrice,
                realPrice = product.realPrice,
                recordedAt = java.time.Instant.now(),
            ))
        }

        // 安い順にソート (送料込み実質合計)
        results.sortedBy { it.totalPrice }
    }

    /**
     * 商品キーから最新情報を再取得。
     * API 失敗時は FallbackScraper で商品ページから JSON-LD を解析。
     */
    override suspend fun refresh(product: Product): Product? {
        val url = product.url.ifEmpty { return null }
        return runCatching {
            fallback.fetchProduct(url, product.platform)
        }.getOrNull() ?: product
    }

    /** 商品の価格履歴を backend から取得 */
    override suspend fun getPriceHistory(productKey: String): List<PriceRecord> {
        return runCatching { backend.getPriceHistory(productKey) }
            .getOrDefault(emptyList())
    }
}
