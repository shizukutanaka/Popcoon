package com.example.popcoon.feature.ethics

/**
 * CO2 + 労働条件の統合エコ倫理スコア。
 * Python 実装 (popcoon_core.py::score_eco_ethics) と完全一致。
 */
object EcoEthicsScorer {

    data class Score(
        val overall: Int,
        val co2Score: Int,
        val laborScore: Int,
        val co2Kg: Double,
        val greenAlternative: String?,
    )

    // 2025 IEA 電力消費 kgCO2/kWh — 簡易近似
    private val CO2_PER_KWH = mapOf(
        "JP" to 0.47,
        "CN" to 0.58,
        "US" to 0.38,
        "DE" to 0.40,
        "VN" to 0.50,
        "TW" to 0.52,
        "KR" to 0.44,
    )

    // 製造時の想定電力 (kWh)
    private val DEVICE_KWH = mapOf(
        "tv" to 850.0,
        "laptop" to 450.0,
        "smartphone" to 90.0,
        "refrigerator" to 1100.0,
        "washer" to 650.0,
    )

    // ILO + Walk Free Foundation の労働権利スコア (0-100)
    private val LABOR_BASE = mapOf(
        "JP" to 82, "CN" to 42, "US" to 78, "DE" to 90, "VN" to 55,
        "TW" to 75, "KR" to 70,
    )

    fun score(
        country: String?,
        category: String,
        certifications: List<String>,
    ): Score {
        val ctry = country?.uppercase() ?: "UNKNOWN"
        val co2PerKwh = CO2_PER_KWH[ctry] ?: 0.55
        val kwh = DEVICE_KWH[category] ?: 300.0
        val co2Kg = kwh * co2PerKwh

        // CO2スコア: 低kwh*係数ほど高得点 (0..100)
        val co2Penalty = (co2Kg / 10).toInt()
        var co2Score = (100 - co2Penalty).coerceIn(0, 100)
        if (certifications.any { it in listOf("エコマーク", "EnergyStar", "EcoLabel") }) {
            co2Score = (co2Score + 10).coerceAtMost(100)
        }

        val laborScore = LABOR_BASE[ctry] ?: 50

        val overall = (co2Score * 0.5 + laborScore * 0.5).toInt()

        val alt = if (overall < 55) "より環境・労働に優しい代替品を検討" else null

        return Score(
            overall = overall,
            co2Score = co2Score,
            laborScore = laborScore,
            co2Kg = co2Kg,
            greenAlternative = alt,
        )
    }
}
