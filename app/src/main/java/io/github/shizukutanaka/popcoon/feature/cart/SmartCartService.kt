package io.github.shizukutanaka.popcoon.feature.cart

import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.feature.matching.ProductMatcher
import io.github.shizukutanaka.popcoon.feature.points.PointSimulator

/**
 * ウォッチリスト → CrossMallCartOptimizer への変換ブリッジ。
 * PointSimulator が前計算した実質単価を `options` に載せる想定（現在は totalPrice で代替）。
 *
 * Google Universal Cart / UCP を見越した先回り機能（UNIVERSAL_CART_SPEC.md）。
 * ProductMatcher で同一商品を束ね、複数モールの価格比較データが揃っている商品を最適化対象とする。
 */
object SmartCartService {

    /** モール別デフォルト設定（2025年日本標準送料・無料ライン）。 */
    val DEFAULT_MALL_CONFIGS = mapOf(
        Platform.AMAZON.id  to CrossMallCartOptimizer.MallConfig(shipping = 450.0,  freeThreshold = 2000.0),
        Platform.RAKUTEN.id to CrossMallCartOptimizer.MallConfig(shipping = 500.0,  freeThreshold = 3300.0),
        Platform.YAHOO.id   to CrossMallCartOptimizer.MallConfig(shipping = 550.0,  freeThreshold = 3500.0),
    )

    data class SmartCartResult(
        val cartItems: List<CrossMallCartOptimizer.CartItem>,
        val optimized: CrossMallCartOptimizer.Result,
        /** ウォッチリスト上の現在プラットフォームで単純購入した場合の合計（送料込み）。 */
        val naiveTotal: Double,
        val savingVsNaive: Double,
    )

    /**
     * ウォッチリストから CartItem リストを構築して最適化する。
     *
     * 同一商品グループは ProductMatcher が判定し、グループ内の各プラットフォームが
     * CartItem の選択肢（options）になる。グループ化できない（ユニーク）商品は
     * 単一オプションとして optimizer に渡す（送料集約の効果だけを受ける）。
     *
     * options に載せる価格は PointSimulator 実質価格 (sticker - points)。
     * CrossMallCartOptimizer が mall-level 送料を別途加算するため、
     * options には product.realPrice ベースの値を使う（totalPrice は shipping 重複になる）。
     */
    fun optimize(
        items: List<WatchlistItem>,
        mallConfigs: Map<String, CrossMallCartOptimizer.MallConfig> = DEFAULT_MALL_CONFIGS,
        userCtx: PointSimulator.UserContext = PointSimulator.UserContext(),
    ): SmartCartResult? {
        if (items.isEmpty()) return null

        val products = items.map { it.toProduct() }
        val groups = ProductMatcher.groupByIdentity(products)

        val cartItems = groups.map { group ->
            // effective price = realPrice - points (shipping 抜き; optimizer が別途 mall shipping を加算)
            val options = group.associate { p ->
                p.platform.id to PointSimulator.simulate(p, userCtx).let {
                    (it.sticker - it.pointsBack).toDouble().coerceAtLeast(0.0)
                }
            }
            CrossMallCartOptimizer.CartItem(
                name = group.first().title.take(50),
                options = options,
            )
        }

        val result = CrossMallCartOptimizer.optimize(cartItems, mallConfigs)

        // 「現状プラン」: 各商品をウォッチ中のプラットフォームから購入した場合の単純合計
        val naiveTotal = computeNaiveTotal(groups, mallConfigs, userCtx)

        return SmartCartResult(
            cartItems = cartItems,
            optimized = result,
            naiveTotal = naiveTotal,
            savingVsNaive = (naiveTotal - result.total).coerceAtLeast(0.0),
        )
    }

    private fun computeNaiveTotal(
        groups: List<List<Product>>,
        mallConfigs: Map<String, CrossMallCartOptimizer.MallConfig>,
        userCtx: PointSimulator.UserContext,
    ): Double {
        // 各グループの first() プラットフォーム（ウォッチ中プラット）で買ったときのモール別合計
        // effective price (sticker - points) を使い、mall shipping を別途加算
        val perMall = mutableMapOf<String, Double>()
        for (group in groups) {
            val p = group.first()
            val mallId = p.platform.id
            val sim = PointSimulator.simulate(p, userCtx)
            val effectiveSticker = (sim.sticker - sim.pointsBack).toDouble().coerceAtLeast(0.0)
            perMall[mallId] = (perMall[mallId] ?: 0.0) + effectiveSticker
        }
        var total = 0.0
        for ((mallId, sub) in perMall) {
            total += sub
            val cfg = mallConfigs[mallId] ?: CrossMallCartOptimizer.MallConfig()
            if (sub > 0.0 && sub < cfg.freeThreshold) total += cfg.shipping
        }
        return total
    }

    private fun WatchlistItem.toProduct() = Product(
        sku = sku,
        title = title,
        platform = Platform.fromId(platform),
        realPrice = realPrice,
        listPrice = listPrice,
        url = url,
        imageUrl = imageUrl,
    )
}
