package com.example.popcoon.feature.ai

import com.example.popcoon.data.model.Product
import com.example.popcoon.feature.scorer.BuyTimingScorer
import java.util.concurrent.ConcurrentHashMap
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

    private val cache = ConcurrentHashMap<String, Entry>()
    private val maxSize = 100
    private val ttlMillis = 24L * 60 * 60 * 1000  // 24時間

    /**
     * キャッシュキー: productKey + scoreBucket。
     * scoreBucket = score / 10 (例: 73 → 7) で「ほぼ同じ」状況を共有。
     */
    private fun keyOf(product: Product, score: BuyTimingScorer.Score): String {
        val scoreBucket = score.total / 10
        val verdict = score.verdict.name
        return "${product.key}|$scoreBucket|$verdict"
    }

    fun get(product: Product, score: BuyTimingScorer.Score): String? {
        val key = keyOf(product, score)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > ttlMillis) {
            cache.remove(key)
            return null
        }
        return entry.advice
    }

    fun put(product: Product, score: BuyTimingScorer.Score, advice: String) {
        val key = keyOf(product, score)
        cache[key] = Entry(advice = advice)
        evictIfNeeded()
    }

    @Synchronized
    private fun evictIfNeeded() {
        if (cache.size <= maxSize) return
        // 最も古いエントリを削除 (LRU 近似)
        val oldest = cache.entries.minByOrNull { it.value.createdAt } ?: return
        cache.remove(oldest.key)
    }

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size
}
