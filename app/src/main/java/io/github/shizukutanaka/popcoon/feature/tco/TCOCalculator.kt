package io.github.shizukutanaka.popcoon.feature.tco

import kotlin.math.max

/**
 * 総所有コスト計算。消耗品・電気代・保守・残存価値を統合。
 * Python 実装 (popcoon_core.py::calculate_tco) と同一式。
 */
object TCOCalculator {

    data class Result(
        val purchasePrice: Long,
        val consumablesTotal: Long,
        val energyTotal: Long,
        val maintenance: Long,
        val residualValue: Long,
        val totalTco: Long,
        val tcoPerMonth: Long,
        val vsAlternative: Alternative? = null,
    )

    /**
     * 代替製品との比較 (Python: `TCOResult.vs_alternative`)。
     * 現状はインクジェットプリンター → インクタンク式のみ。カテゴリが増えたら
     * kind を増やし、ラベルは呼び出し側 (UI) でローカライズする (生の日本語文字列を
     * data class に持たせない — 既存の kind/label 分離パターンに合わせる)。
     */
    enum class AlternativeKind { INK_TANK_PRINTER }

    data class Alternative(
        val kind: AlternativeKind,
        val altTco: Long,
        val savings: Long,
    )

    private data class Energy(val watts: Int, val hoursPerDay: Double)

    private val ENERGY_MAP = mapOf(
        "inkjet_printer" to Energy(15, 0.5),
        "laser_printer" to Energy(400, 0.5),
        "laptop" to Energy(45, 6.0),
        "refrigerator" to Energy(35, 24.0),
        "air_conditioner" to Energy(700, 8.0),
    )

    fun calculate(
        purchasePrice: Long,
        category: String,
        years: Int = 5,
        intensity: Double = 1.0,
    ): Result {
        require(years > 0) { "years must be positive" }
        require(intensity > 0) { "intensity must be positive" }

        // Python (popcoon_core.calculate_tco) と完全一致させる:
        //  - 各消耗品は int(price * (基本数 * intensity))。intensity を先に掛けて結合順も合わせる。
        //  - レーザーのドラムは Python では intensity 非適用 (0.33/年 固定)。以前は intensity を掛けており、
        //    i≠1.0 のレーザープリンタで消耗品コスト＝TCO がずれていた (差分パリティで検出した実バグ)。
        val consumablesYearly = when (category) {
            "inkjet_printer" -> {
                val inkBlack = (1800 * (6.0 * intensity)).toLong()
                val inkColor = (2200 * (4.0 * intensity)).toLong()
                val paper = (800 * (2.0 * intensity)).toLong()
                inkBlack + inkColor + paper
            }
            "laser_printer" -> {
                val toner = (6000 * (1.5 * intensity)).toLong()
                val drum = (8000 * 0.33).toLong()    // intensity 非適用 (Python と一致)
                val paper = (600 * (3.0 * intensity)).toLong()
                toner + drum + paper
            }
            "coffee_capsule" -> (80 * (365.0 * intensity)).toLong()
            else -> 0L
        }
        val consumablesTotal = consumablesYearly * years

        val energyTotal = ENERGY_MAP[category]?.let { (w, h) ->
            (w * h * 365 / 1000 * 27).toLong() * years
        } ?: 0L

        val maintenance = when {
            years in 4..6 -> purchasePrice / 10
            years >= 7 -> purchasePrice / 6
            else -> 0L
        }

        val residualRate = when (category) {
            "smartphone" -> max(0.0, 0.5 - years * 0.12)
            "laptop" -> max(0.0, 0.4 - years * 0.08)
            "inkjet_printer" -> max(0.0, 0.05 - years * 0.01)
            else -> max(0.0, 0.05 - years * 0.01)
        }
        val residual = (purchasePrice * residualRate).toLong()

        val tco = purchasePrice + consumablesTotal + energyTotal + maintenance - residual
        val monthly = tco / (years * 12)

        // インクジェット vs インクタンク式の代替比較 (Python: calculate_tco の vs_alt と同一式)。
        val vsAlternative = if (category == "inkjet_printer") {
            val altTco = purchasePrice * 3 + 10_000L + 3_000L * years
            Alternative(kind = AlternativeKind.INK_TANK_PRINTER, altTco = altTco, savings = tco - altTco)
        } else {
            null
        }

        return Result(
            purchasePrice = purchasePrice,
            consumablesTotal = consumablesTotal,
            energyTotal = energyTotal,
            maintenance = maintenance,
            residualValue = residual,
            totalTco = tco,
            tcoPerMonth = monthly,
            vsAlternative = vsAlternative,
        )
    }

    /**
     * 商品タイトルから TCO 対象カテゴリを推定する。
     *
     * TCO (総保有コスト) が購入価格と大きく乖離するのは消耗品・電力を伴う製品。
     * 該当しない商品では TCO 表示は無意味なため null を返す。
     *
     * @return ENERGY_MAP / consumables 対応カテゴリ、該当なしは null
     */
    fun inferCategory(title: String): String? {
        val t = title.lowercase()
        return when {
            t.contains("インクジェット") || (t.contains("プリンター") && !t.contains("レーザー")) ->
                "inkjet_printer"
            t.contains("レーザープリンター") || t.contains("レーザー複合機") ->
                "laser_printer"
            // RESIDUAL_RATE_DB (calculate() 内) には "smartphone" 用の残存価値式が
            // 元々存在したが、ここに検出条件が無かったため実商品では一度も到達できない
            // 死んだ分岐だった (機能過不足監査で発見)。5年で残存価値がほぼ0になる
            // 他カテゴリと異なり、中古スマホ市場は現実的な残存価値を持つため意義が大きい。
            t.contains("スマホ") || t.contains("スマートフォン") || t.contains("iphone") ||
                t.contains("android") || t.contains("携帯電話") ->
                "smartphone"
            t.contains("ノートpc") || t.contains("ノートパソコン") || t.contains("laptop") ->
                "laptop"
            t.contains("冷蔵庫") || t.contains("refrigerator") ->
                "refrigerator"
            t.contains("エアコン") || t.contains("air conditioner") ->
                "air_conditioner"
            t.contains("コーヒーメーカー") || t.contains("カプセル") ->
                "coffee_capsule"
            else -> null
        }
    }
}
