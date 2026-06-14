import com.example.popcoon.data.model.PriceRecord
import com.example.popcoon.feature.cart.CrossMallCartOptimizer
import com.example.popcoon.feature.crossborder.CustomsSimulator
import com.example.popcoon.feature.darkpattern.DarkPatternDetector
import com.example.popcoon.feature.ethics.EcoEthicsScorer
import com.example.popcoon.feature.prediction.ConformalInterval
import com.example.popcoon.feature.prediction.PricePredictionEngine
import com.example.popcoon.feature.prediction.SeasonalDecompForecast
import com.example.popcoon.feature.scorer.BuyTimingScorer
import java.time.Instant

/**
 * クロス言語パリティ実行ハーネス。
 *
 * 目的: "Python parity" を文書上の主張ではなく**実行可能な検証**にする。
 * 本物の Kotlin 実装を Gradle 同梱の kotlin-compiler-embeddable でコンパイルし JVM で実行 →
 * 各入力に対する出力を TSV で印字。compare_oracle.py が同じ入力で検証済み Python オラクル
 * (popcoon_core / buy_timing_scorer) を再計算して照合する。入力を印字して Python が
 * 再計算するので fixture との drift が起き得ない。Android SDK 不要。run.sh から実行する。
 *
 * カバー: scalar (customs/eco) + 履歴依存 (dark-pattern/predict/buy-timing)。
 * buy-timing は today=null で呼ぶ (Kotlin 拡張の sale/dow シグナルを除いた Python 同等経路)。
 */

// 決定論的履歴: productKey="k", platform="amazon", recorded_at = 2026-01-01 + i 日 UTC。
// Python 側 compare_oracle.py と同一規約。
private val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")
private fun hist(prices: List<Long>, listPrice: Long): List<PriceRecord> =
    prices.mapIndexed { i, p ->
        PriceRecord("k", "amazon", listPrice, p, BASE.plusSeconds(i.toLong() * 86_400))
    }

private fun csv(xs: List<Long>) = xs.joinToString(",")

fun main() {
    // ── CUSTOMS ──────────────────────────────────────────────────────────────
    data class C(val f: Long, val s: Long, val cat: String, val jp: Long?)
    listOf(
        C(10_000, 5_000, "衣類", null), C(20_000, 5_000, "靴", null),
        C(50_000, 5_000, "電子機器", null), C(10_000, 2_000, "食品", 50_000),
        C(20_000, 2_000, "食品", 40_000), C(40_000, 5_000, "食品", 30_000),
        C(20_000, 2_000, "衣類", 40_000), C(10_000, 6_666, "衣類", null),
        C(30_000, 3_000, "靴", null), C(0, 0, "電子機器", null),
        C(15_000, 5_000, "化粧品", 18_000),
    ).forEach { c ->
        val r = CustomsSimulator.simulate(c.f, c.s, c.cat, c.jp)
        println("CUSTOMS\t${c.f}\t${c.s}\t${c.cat}\t${c.jp ?: "null"}\t" +
            "${r.totalLandedCost}\t${r.customsDuty}\t${r.consumptionTax}\t${r.isTaxExempt}\t${r.verdict}")
    }

    // ── ECO ──────────────────────────────────────────────────────────────────
    data class E(val origin: String?, val cat: String, val certs: List<String>)
    listOf(
        E("CN", "smartphone", emptyList()), E("JP", "laptop", emptyList()),
        E("DE", "tv", listOf("green-cert")), E("VN", "tshirt", emptyList()),
        E(null, "smartphone", emptyList()), E("US", "unknown_category", emptyList()),
        E("CN", "tshirt", listOf("エコ認証")),
    ).forEach { e ->
        val s = EcoEthicsScorer.score(e.origin, e.cat, e.certs)
        println("ECO\t${e.origin ?: "null"}\t${e.cat}\t${e.certs.joinToString(";")}\t" +
            "${s.overall}\t${s.co2Score}\t${s.laborScore}\t${"%.6f".format(s.co2Kg)}\t${s.greenAlternative ?: "null"}")
    }

    // ── 履歴依存シナリオ (dark-pattern / predict / buy-timing 共通入力) ─────────
    data class H(val current: Long, val listPrice: Long, val prices: List<Long>)
    val flat90 = List(90) { 10_000L }
    val desc30 = (0 until 30).map { 15_000L - it * 100 }
    val asc14 = (0 until 14).map { 10_000L + it * 100 }
    val charm = List(40) { 9_980L }                       // 端数価格 (下二桁80-99)
    val inflated = List(20) { 5_000L }                    // list>max*1.5 で参考価格誇張
    val presale = List(7) { 8_000L } + List(7) { 9_200L } // 直近7日が前7日比+15%
    val scenarios = listOf(
        H(10_000, 12_000, flat90),
        H(desc30.last(), 20_000, desc30),
        H(asc14.last(), 14_000, asc14),
        H(9_980, 12_000, charm),
        H(5_000, 20_000, inflated),
        H(9_200, 12_000, presale),
        H(10_000, 12_000, List(13) { 10_000L }),          // 履歴不足 -> buy_timing null
    )
    for (h in scenarios) {
        val records = hist(h.prices, h.listPrice)

        // DARKPATTERN: sorted warning type names
        val warns = DarkPatternDetector.detect(h.current, if (h.listPrice <= 0) null else h.listPrice, records)
            .map { it.type.name }.sorted().joinToString(",")
        println("DARKPATTERN\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\t$warns")

        // PREDICT: Python と共有するフィールドのみ (margin/seasonal は Kotlin 拡張なので除外)
        val pred = PricePredictionEngine.predict(records)
        if (pred == null) {
            println("PREDICT\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\tnull")
        } else {
            println("PREDICT\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\t" +
                "${pred.predicted7d}\t${pred.predicted30d}\t${"%.4f".format(pred.buyNowProbability)}\t" +
                "${pred.historicLow}\t${pred.historicHigh}\t${pred.confidence}")
        }

        // BUYTIMING: today=null (Python 同等経路)
        val bt = BuyTimingScorer.score(h.current, h.listPrice, records, null)
        if (bt == null) {
            println("BUYTIMING\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\tnull")
        } else {
            println("BUYTIMING\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\t${bt.total}\t${bt.verdict}\t${bt.confidence}")
        }
    }

    // ── CONFORMAL: split-conformal margin (浮動小数点境界を含む) ────────────────
    // residuals(';' 区切り Double) と alpha → margin。proto_conformal_interval と照合。
    data class CC(val residuals: List<Double>, val alpha: Double)
    val conformal = listOf(
        CC((1..10).map { it.toDouble() }, 0.1),
        CC((1..9).map { it.toDouble() }, 0.1),     // 10*0.9 の浮動小数点境界
        CC((1..9).map { it.toDouble() }, 0.05),
        CC(listOf(-5.0, 3.0, -1.0, 4.0, 2.0), 0.2),
        CC(listOf(7.5), 0.1),                       // 単一: k>n → max
        CC((1..100).map { it.toDouble() }, 0.05),
        CC(listOf(2.0, 2.0, 2.0), 0.33),
        CC(emptyList(), 0.1),                        // 空 → 0.0
    )
    for (c in conformal) {
        val m = ConformalInterval.conformalMargin(c.residuals, c.alpha)
        println("CONFORMAL\t${c.residuals.joinToString(";")}\t${c.alpha}\t${"%.10f".format(m)}")
    }

    // ── SEASONAL: trend+seasonal 分解予測 (中心移動平均 + 最小二乗線形) ──────────
    // 価格列・horizon・period → 予測列。proto_seasonal_decomp_forecast と照合。
    data class SC(val prices: List<Double>, val horizon: Int, val period: Int)
    fun weekly(nDays: Int) = (0 until nDays).map { i ->
        1000.0 + 10 * i + when (i % 7) { 5 -> -50.0; 6 -> -30.0; 0 -> 20.0; else -> 0.0 }
    }
    val seasonal = listOf(
        SC(weekly(28), 7, 7),                                   // 4 週・週次季節性 + トレンド
        SC(weekly(21), 7, 7),                                   // 3 週
        SC(weekly(14), 7, 7),                                   // min_history 境界 (=14)
        SC(weekly(10), 7, 7),                                   // < min_history → フラット
        SC((0 until 20).map { 500.0 + 3.5 * it }, 5, 1),        // period=1 → 純線形
        SC((0 until 15).map { 800.0 - 2.0 * it + if (it % 5 == 0) 15.0 else 0.0 }, 5, 5),  // period=5
    )
    for (sc in seasonal) {
        val f = SeasonalDecompForecast.forecast(sc.prices, sc.horizon, sc.period)
        println("SEASONAL\t${sc.prices.joinToString(";")}\t${sc.horizon}\t${sc.period}\t" +
            f.joinToString(";") { "%.10f".format(it) })
    }

    // ── CART: cross-mall basket optimizer (組合せ最適化 + タイブレーク) ──────────
    // 入力 (items/malls) を # | , = で符号化して emit → Python が同じ入力で再計算し照合。
    // item= name#qty#mall=price,mall=price   mall= id#shipping#free#thr=disc,thr=disc
    fun encItems(items: List<CrossMallCartOptimizer.CartItem>) =
        items.joinToString("|") { ci ->
            "${ci.name}#${ci.qty}#" + ci.options.entries.joinToString(",") { e -> "${e.key}=${e.value}" }
        }
    fun encMalls(malls: Map<String, CrossMallCartOptimizer.MallConfig>) =
        malls.entries.joinToString("|") { (id, c) ->
            "$id#${c.shipping}#${c.freeThreshold}#" + c.coupons.joinToString(",") { cp -> "${cp.threshold}=${cp.discount}" }
        }
    fun item(name: String, qty: Int, vararg opts: Pair<String, Double>) =
        CrossMallCartOptimizer.CartItem(name, linkedMapOf(*opts), qty)
    fun mall(ship: Double, free: Double, vararg cps: Pair<Double, Double>) =
        CrossMallCartOptimizer.MallConfig(ship, free, cps.map { CrossMallCartOptimizer.Coupon(it.first, it.second) })

    data class Cart(val items: List<CrossMallCartOptimizer.CartItem>, val malls: Map<String, CrossMallCartOptimizer.MallConfig>)
    val carts = listOf(
        // 2 商品: 分割すると送料がかかるが、まとめると送料無料ライン超え
        Cart(listOf(item("A", 1, "amazon" to 1500.0, "rakuten" to 1600.0),
                    item("B", 1, "amazon" to 1800.0, "rakuten" to 1700.0)),
             mapOf("amazon" to mall(500.0, 3000.0), "rakuten" to mall(400.0, 3000.0))),
        // クーポンが効くケース
        Cart(listOf(item("A", 2, "rakuten" to 2000.0, "yahoo" to 2100.0),
                    item("B", 1, "rakuten" to 3000.0, "yahoo" to 2900.0)),
             mapOf("rakuten" to mall(400.0, 10000.0, 5000.0 to 500.0, 8000.0 to 1000.0),
                   "yahoo" to mall(350.0, 8000.0))),
        // 同額タイ → 配送回数が少ない (単一モール) を優先
        Cart(listOf(item("A", 1, "amazon" to 1000.0, "rakuten" to 1000.0),
                    item("B", 1, "amazon" to 1000.0, "rakuten" to 1000.0)),
             mapOf("amazon" to mall(0.0, 0.0), "rakuten" to mall(0.0, 0.0))),
        // 単一商品
        Cart(listOf(item("solo", 3, "amazon" to 800.0, "yahoo" to 790.0)),
             mapOf("amazon" to mall(500.0, 5000.0), "yahoo" to mall(600.0, 5000.0))),
        // 3 商品 3 モール
        Cart(listOf(item("A", 1, "amazon" to 1200.0, "rakuten" to 1250.0, "yahoo" to 1180.0),
                    item("B", 1, "amazon" to 900.0, "rakuten" to 880.0, "yahoo" to 950.0),
                    item("C", 2, "amazon" to 600.0, "rakuten" to 610.0, "yahoo" to 590.0)),
             mapOf("amazon" to mall(450.0, 3500.0), "rakuten" to mall(400.0, 3000.0, 2000.0 to 200.0),
                   "yahoo" to mall(500.0, 4000.0))),
    )
    for (cart in carts) {
        val r: CrossMallCartOptimizer.Result = CrossMallCartOptimizer.optimize(cart.items, cart.malls)
        val assignParts = ArrayList<String>()
        for (i in 0 until cart.items.size) {
            assignParts.add(i.toString() + "=" + r.assignment[i])
        }
        val assign = assignParts.joinToString(",")
        println("CART\t${encItems(cart.items)}\t${encMalls(cart.malls)}\t" +
            "${"%.6f".format(r.total)}#${r.numMalls}#${"%.6f".format(r.shippingTotal)}#${"%.6f".format(r.couponTotal)}#$assign#${r.greedy}")
    }
}
