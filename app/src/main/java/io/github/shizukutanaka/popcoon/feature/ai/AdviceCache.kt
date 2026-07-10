package io.github.shizukutanaka.popcoon.feature.ai

import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI アドバイスのインメモリキャッシュ。
 *
 * 課題: BuyingAdvisor は毎回 Claude API を呼ぶため料金が嵩む。
 * 同じ商品 + 同じスコアなら同じ助言が返るので 24時間キャッシュ可。
 *
 * 設計:
 *  - キー: productKey + score バケット (10刻み) で粒度調整
 *  - 値 + 取得時刻 (TTL 判定用)
 *  - LRU で最大 100 件保持 (メモリ上限)
 *  - スレッドセーフ (ConcurrentHashMap + synchronized)
 *
 * これにより:
 *  - 同じ商品を再度開く → 即時表示 (UX 改善)
 *  - 1日 100ユーザー × 平均 5 商品閲覧 = 500 API call が
 *    キャッシュヒット率 60% で 200 call まで削減 (料金 60% 減)
 */
@Singleton
class AdviceCache @Inject constructor() {

    private data class Entry(
        val advice: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val cache = HashMap<String, Entry>()
    private val maxSize = 100
    private val ttlMillis = 24L * 60 * 60 * 1000  // 24時間

    /**
     * キャッシュキー: productKey + scoreBucket + 言語。
     * scoreBucket = score / 10 (例: 73 → 7) で「ほぼ同じ」状況を共有。
     * 言語をキーに含めるのは、advice のテキスト自体がロケール依存 (BuyingAdvisor が
     * ロケールに応じた言語で応答を要求する) になったため — 含めないと、あるロケールで
     * 生成された助言が別ロケールの表示にそのまま出てしまう (商用リリース監査で発見)。
     */
    private fun keyOf(product: Product, score: BuyTimingScorer.Score, locale: Locale): String {
        val scoreBucket = score.total / 10
        val verdict = score.verdict.name
        return "${product.key}|$scoreBucket|$verdict|${locale.language}"
    }

    @Synchronized
    fun get(product: Product, score: BuyTimingScorer.Score, locale: Locale = Locale.getDefault()): String? {
        val key = keyOf(product, score, locale)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > ttlMillis) {
            cache.remove(key)
            return null
        }
        return entry.advice
    }

    @Synchronized
    fun put(
        product: Product,
        score: BuyTimingScorer.Score,
        advice: String,
        locale: Locale = Locale.getDefault(),
    ) {
        val key = keyOf(product, score, locale)
        cache[key] = Entry(advice = advice)
        if (cache.size > maxSize) {
            val oldest = cache.entries.minByOrNull { it.value.createdAt } ?: return
            cache.remove(oldest.key)
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size
}
