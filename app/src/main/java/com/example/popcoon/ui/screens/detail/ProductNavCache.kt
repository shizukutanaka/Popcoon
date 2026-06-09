package com.example.popcoon.ui.screens.detail

import com.example.popcoon.data.model.Product

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
 * スレッド安全性:
 *  @Synchronized で check-then-act を原子化。LinkedHashMap で挿入順 FIFO を保証。
 */
object ProductNavCache {
    private val cache = LinkedHashMap<String, Product>()
    private const val MAX_ENTRIES = 20

    /** 遷移前に Product を登録 */
    @Synchronized
    fun put(product: Product) {
        if (cache.size >= MAX_ENTRIES) {
            cache.keys.firstOrNull()?.let { cache.remove(it) }
        }
        cache[product.key] = product
    }

    /** 遷移後に取り出し、同時に削除 (1 回限り) */
    @Synchronized
    fun consume(key: String): Product? = cache.remove(key)

    /** テスト用 */
    @Synchronized
    fun clear() = cache.clear()
}
