package com.example.popcoon.ui.screens.detail

import com.example.popcoon.data.model.Product
import java.util.concurrent.ConcurrentHashMap

/**
 * 画面間で Product を受け渡すためのインメモリキャッシュ。
 *
 * 設計理由:
 *  Compose Navigation の savedStateHandle は **遷移元と遷移先で別インスタンス** のため、
 *  `currentBackStackEntry.savedStateHandle[...] = product` で保存しても
 *  遷移先 ViewModel の savedStateHandle には届かない (典型的な落とし穴)。
 *
 *  かといって Product 全フィールドを route 引数にエンコードすると URL が壊れやすく、
 *  Parcelable 化は Compose Nav では非推奨。
 *
 *  → productKey をキーにした軽量キャッシュで橋渡しする。
 *    - 検索画面: navigate 前に put(product)
 *    - 詳細画面: load 時に consume(key) で取り出し (1回限り)
 *    - 取得後は削除しメモリリーク防止
 *    - キャッシュミス時は productKey からフォールバック構築 (既存ロジック)
 *
 * スレッド安全性: ConcurrentHashMap で複数遷移を保護。
 */
object ProductNavCache {
    private val cache = ConcurrentHashMap<String, Product>()
    private const val MAX_ENTRIES = 20

    /** 遷移前に Product を登録 */
    fun put(product: Product) {
        // 上限超過時は最古を捨てる (FIFO 近似、厳密 LRU 不要)
        if (cache.size >= MAX_ENTRIES) {
            cache.keys.firstOrNull()?.let { cache.remove(it) }
        }
        cache[product.key] = product
    }

    /** 遷移後に取り出し、同時に削除 (1 回限り) */
    fun consume(key: String): Product? = cache.remove(key)

    /** テスト用 */
    fun clear() = cache.clear()
}
