import com.example.popcoon.data.model.PriceRecord
import com.example.popcoon.feature.crossborder.CustomsSimulator
import com.example.popcoon.feature.darkpattern.DarkPatternDetector
import com.example.popcoon.feature.ethics.EcoEthicsScorer
import com.example.popcoon.feature.prediction.ConformalInterval
import com.example.popcoon.feature.prediction.PricePredictionEngine
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
}
