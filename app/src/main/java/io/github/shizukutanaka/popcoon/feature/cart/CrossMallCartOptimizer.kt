package io.github.shizukutanaka.popcoon.feature.cart

/**
 * 横断スマートカートの中核: 複数モールに分かれて買うとき、送料無料ライン・モールクーポン・
 * （実質単価に織り込んだ）ポイントを考慮して**カート実質総額を最小化**するモール割り当てを求める。
 *
 * Google Universal Cart を見越した先回り機能（UNIVERSAL_CART_FEATURE.md / UNIVERSAL_CART_SPEC.md）。
 * Python 参照 (popcoon-tdd/proto_cross_mall_cart.py) と完全一致。
 * パリティは CrossMallCartOptimizerTest（ゴールデンベクタ）で保証（PORTING_SPEC.md #4）。
 *
 * 実質単価は PointSimulator、同一商品の横断同定は ProductMatcher で前計算する想定。
 */
object CrossMallCartOptimizer {

    data class Coupon(val threshold: Double, val discount: Double)

    data class MallConfig(
        val shipping: Double = 0.0,
        val freeThreshold: Double = 0.0,
        val coupons: List<Coupon> = emptyList(),
    )

    /** options: mall_id -> 実質単価（税込 - ポイント - クーポン、送料除く）。 */
    data class CartItem(
        val name: String,
        val options: Map<String, Double>,
        val qty: Int = 1,
    )

    data class Result(
        val assignment: Map<Int, String>,
        val total: Double,
        val perMallSubtotal: Map<String, Double>,
        val shippingTotal: Double,
        val couponTotal: Double,
        val numMalls: Int,
        val greedy: Boolean = false,
    )

    private fun bestCoupon(subtotal: Double, coupons: List<Coupon>): Double {
        var best = 0.0
        for (c in coupons) if (subtotal >= c.threshold) best = maxOf(best, c.discount)
        return best
    }

    private fun cost(
        items: List<CartItem>,
        malls: Map<String, MallConfig>,
        combo: List<String>,
    ): Result {
        val subtotal = LinkedHashMap<String, Double>()
        for (i in items.indices) {
            val m = combo[i]
            subtotal[m] = (subtotal[m] ?: 0.0) + items[i].options.getValue(m) * items[i].qty
        }
        var shippingTotal = 0.0
        var couponTotal = 0.0
        for ((m, sub) in subtotal) {
            val cfg = malls[m] ?: MallConfig()
            couponTotal += bestCoupon(sub, cfg.coupons)
            if (sub > 0.0 && sub < cfg.freeThreshold) shippingTotal += cfg.shipping
        }
        val numMalls = subtotal.values.count { it > 0.0 }
        val total = subtotal.values.sum() - couponTotal + shippingTotal
        return Result(emptyMap(), total, subtotal, shippingTotal, couponTotal, numMalls)
    }

    /** 実質総額を最小化する割り当て。小規模は全探索で厳密最適、大規模は貪欲フォールバック。 */
    fun optimize(
        items: List<CartItem>,
        malls: Map<String, MallConfig>,
        bruteCap: Int = 200_000,
    ): Result {
        val n = items.size
        if (n == 0) return Result(emptyMap(), 0.0, emptyMap(), 0.0, 0.0, 0)

        val choiceLists = items.map { it.options.keys.sorted() }
        require(choiceLists.all { it.isNotEmpty() }) {
            "each item must have at least one mall option"
        }

        var size = 1L
        for (c in choiceLists) {
            size *= c.size
            if (size > bruteCap) break
        }

        if (size <= bruteCap) {
            var bestKey: Triple<Double, Int, List<String>>? = null
            var best: Result? = null
            var bestCombo: List<String>? = null
            val idx = IntArray(n)
            while (true) {
                val combo = List(n) { choiceLists[it][idx[it]] }
                val detail = cost(items, malls, combo)
                val key = Triple(detail.total, detail.numMalls, combo)
                if (bestKey == null || compareKey(key, bestKey) < 0) {
                    bestKey = key
                    best = detail
                    bestCombo = combo
                }
                var p = n - 1
                while (p >= 0) {
                    idx[p]++
                    if (idx[p] < choiceLists[p].size) break
                    idx[p] = 0
                    p--
                }
                if (p < 0) break
            }
            val assignment = checkNotNull(bestCombo) { "brute-force loop ran zero iterations" }
                .withIndex().associate { (i, m) -> i to m }
            return checkNotNull(best) { "brute-force loop ran zero iterations" }.copy(assignment = assignment)
        }

        // 貪欲フォールバック: item ごとに最安モール（送料・クーポン無視）
        // require(isNotEmpty) 済みなので minBy は安全。
        val combo = items.map { it.options.minBy { e -> e.value }.key }
        val detail = cost(items, malls, combo)
        val assignment = combo.withIndex().associate { (i, m) -> i to m }
        return detail.copy(assignment = assignment, greedy = true)
    }

    /** (実質総額, 配送回数, combo) の辞書順比較。 */
    private fun compareKey(
        a: Triple<Double, Int, List<String>>,
        b: Triple<Double, Int, List<String>>,
    ): Int {
        if (a.first != b.first) return a.first.compareTo(b.first)
        if (a.second != b.second) return a.second.compareTo(b.second)
        val la = a.third
        val lb = b.third
        for (i in la.indices) {
            val cmp = la[i].compareTo(lb[i])
            if (cmp != 0) return cmp
        }
        return 0
    }
}
