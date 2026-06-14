package com.example.popcoon.feature.ethics

/**
 * CO2 + 労働条件の統合エコ倫理スコア。
 *
 * Python 実装 (popcoon_core.py::score_eco_ethics) と完全一致 (定数・式・丸め)。
 * 乖離検知のため EcoEthicsScorerTest に Python 出力との絶対値パリティテストを置く。
 *
 * 注意: Python と同様に国コードは大小区別あり (キーは大文字 "JP"/"CN" 等)。
 */
object EcoEthicsScorer {

    data class Score(
        val overall: Int,
        val co2Score: Int,
        val laborScore: Int,
        val co2Kg: Double,
        val greenAlternative: String?,
    )

    // 国別 CO2 係数 (相対) — popcoon_core.CO2_BY_COUNTRY と一致
    private val CO2_BY_COUNTRY = mapOf(
        "JP" to 0.45, "DE" to 0.30, "US" to 0.38, "CN" to 0.78,
        "VN" to 0.65, "BD" to 0.70, "IN" to 0.72, "KR" to 0.50,
    )

    // 国別 労働権利スコア (0-100) — popcoon_core.LABOR_BY_COUNTRY と一致
    private val LABOR_BY_COUNTRY = mapOf(
        "JP" to 82, "DE" to 90, "US" to 78, "CN" to 52,
        "VN" to 48, "BD" to 40, "IN" to 55, "KR" to 72,
    )

    // カテゴリ別 基準 CO2 (kg) — popcoon_core.CO2_BY_CATEGORY と一致
    private val CO2_BY_CATEGORY = mapOf(
        "smartphone" to 70.0,
        "laptop" to 300.0,
        "tv" to 400.0,
        "tshirt" to 8.0,
    )

    fun score(
        country: String?,
        category: String,
        certifications: List<String>,
    ): Score {
        val co2Factor = CO2_BY_COUNTRY[country] ?: 0.60
        val laborFactor = LABOR_BY_COUNTRY[country] ?: 55
        val baseCo2 = CO2_BY_CATEGORY[category] ?: 50.0
        val co2Estimate = baseCo2 * (co2Factor / 0.45)

        var co2Score = when {
            co2Estimate < baseCo2 * 0.7 -> 80
            co2Estimate < baseCo2 -> 65
            co2Estimate < baseCo2 * 1.5 -> 45
            else -> 25
        }
        if (certifications.any { it.contains("エコ") || it.lowercase().contains("green") }) {
            co2Score = minOf(100, co2Score + 10)
        }

        val overall = (co2Score * 0.35 + laborFactor * 0.30 + 60 * 0.20 + 70 * 0.15).toInt()

        // 原産国が日本より低炭素 (co2Factor < 0.45、例: DE 0.30 / US 0.38) の場合 savingPct <= 0。
        // 「国産代替で削減」は成立しない (むしろ増加) ため提案しない。負の削減率を表示するバグだった。
        val greenAlt = if (country != "JP" && CO2_BY_CATEGORY.containsKey(category)) {
            val savingPct = ((1 - 0.45 / co2Factor) * 100).toInt()
            if (savingPct > 0) "国産代替でCO2${savingPct}%削減可" else null
        } else {
            null
        }

        return Score(
            overall = overall.coerceIn(0, 100),
            co2Score = co2Score.coerceIn(0, 100),
            laborScore = laborFactor.coerceIn(0, 100),
            co2Kg = co2Estimate,
            greenAlternative = greenAlt,
        )
    }
}
