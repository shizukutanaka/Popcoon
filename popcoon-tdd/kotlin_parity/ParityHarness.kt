import com.example.popcoon.feature.crossborder.CustomsSimulator
import com.example.popcoon.feature.ethics.EcoEthicsScorer

/**
 * クロス言語パリティ実行ハーネス (scalar 関数: customs / eco)。
 *
 * 目的: "Python parity" を文書上の主張ではなく**実行可能な検証**にする。
 * 本物の Kotlin 実装 (CustomsSimulator / EcoEthicsScorer) を Gradle 同梱の
 * kotlin-compiler-embeddable でコンパイルし JVM で実行 → 各入力に対する出力を TSV で印字。
 * compare_oracle.py が同じ入力で検証済み Python オラクル (popcoon_core) を再計算し照合する。
 * 入力を印字して Python が再計算するので fixture との drift が起き得ない。
 *
 * Android SDK 不要。run.sh から実行する。
 * 履歴依存関数 (BuyTimingScorer 等) は PriceRecord (kotlinx-serialization plugin) を要するため
 * 別途拡張 (README 参照)。
 */
fun main() {
    data class C(val f: Long, val s: Long, val cat: String, val jp: Long?)
    val customs = listOf(
        C(10_000, 5_000, "衣類", null),
        C(20_000, 5_000, "靴", null),
        C(50_000, 5_000, "電子機器", null),
        C(10_000, 2_000, "食品", 50_000),    // 免税級の掘り出し物 -> CHEAPER
        C(20_000, 2_000, "食品", 40_000),    // 中途半端な節約 -> NOT_RECOMMENDED
        C(40_000, 5_000, "食品", 30_000),    // 国内以上 -> MORE_EXPENSIVE
        C(20_000, 2_000, "衣類", 40_000),    // 非食品 同帯 -> CHEAPER
        C(10_000, 6_666, "衣類", null),      // 免税ぴったり
        C(30_000, 3_000, "靴", null),
        C(0, 0, "電子機器", null),
        C(15_000, 5_000, "化粧品", 18_000),
    )
    for (c in customs) {
        val r = CustomsSimulator.simulate(c.f, c.s, c.cat, c.jp)
        println("CUSTOMS\t${c.f}\t${c.s}\t${c.cat}\t${c.jp ?: "null"}\t" +
            "${r.totalLandedCost}\t${r.customsDuty}\t${r.consumptionTax}\t${r.isTaxExempt}\t${r.verdict}")
    }

    data class E(val origin: String?, val cat: String, val certs: List<String>)
    val eco = listOf(
        E("CN", "smartphone", emptyList()),
        E("JP", "laptop", emptyList()),
        E("DE", "tv", listOf("green-cert")),
        E("VN", "tshirt", emptyList()),
        E(null, "smartphone", emptyList()),
        E("US", "unknown_category", emptyList()),
        E("CN", "tshirt", listOf("エコ認証")),
    )
    for (e in eco) {
        val s = EcoEthicsScorer.score(e.origin, e.cat, e.certs)
        println("ECO\t${e.origin ?: "null"}\t${e.cat}\t${e.certs.joinToString(";")}\t" +
            "${s.overall}\t${s.co2Score}\t${s.laborScore}\t${"%.6f".format(s.co2Kg)}\t${s.greenAlternative ?: "null"}")
    }
}
