package com.example.popcoon.feature.bundle

/**
 * セット販売の実質単価計算 + お得判定。
 * Python 実装 (bundle_pack_detector.py) と 100% 等価。
 */
object BundlePackDetector {

    enum class Verdict {
        EXCEPTIONAL_DEAL, GOOD_DEAL, NEUTRAL, BAD_DEAL, NOT_A_BUNDLE, UNKNOWN
    }

    data class Analysis(
        val bundlePrice: Long,
        val packCount: Int,
        val singlePrice: Long?,
        val unitPriceInBundle: Long,
        val savingsPerUnit: Long?,
        val savingsPercent: Double?,
        val verdict: Verdict,
    )

    data class BundleInfo(val packCount: Int, val unitHint: String?, val isBulk: Boolean)

    private val BUNDLE_PATTERNS = listOf(
        Regex("""(\d+)\s*(本|個|袋|パック|缶|枚|包|粒|組)\s*(セット|まとめ|まとめ買い|ケース)""") to 0.95,
        Regex("""[×x]\s*(\d+)\s*(本|個|袋|枚|パック)""") to 0.90,
        Regex("""(\d+)\s*(本|枚|個|袋|粒|パック|缶|包|組)\s*入""") to 0.85,
        Regex("""(\d+)\s*(パック|缶|箱|ケース)""") to 0.80,
        Regex("""(\d+)\s*(本|袋|個|枚)\s*(?:セット)?""") to 0.60,
    )

    fun extractBundleInfo(title: String?): BundleInfo? {
        if (title.isNullOrEmpty()) return null
        var best: Pair<Int, String>? = null
        var bestConf = 0.0
        for ((pattern, confidence) in BUNDLE_PATTERNS) {
            for (m in pattern.findAll(title)) {
                val count = m.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
                if (count <= 0) continue
                if (confidence > bestConf) {
                    best = count to (m.groupValues.getOrNull(2) ?: "")
                    bestConf = confidence
                }
            }
        }
        val result = best ?: return null
        val (count, unit) = result
        return BundleInfo(packCount = count, unitHint = unit, isBulk = count >= 50)
    }

    fun detectValue(
        bundlePrice: Long,
        packCount: Int,
        singlePrice: Long?,
    ): Analysis {
        require(packCount > 0) { "packCount must be positive, got $packCount" }
        require(bundlePrice >= 0) { "bundlePrice must be non-negative, got $bundlePrice" }
        require(singlePrice == null || singlePrice >= 0) { "singlePrice must be non-negative" }

        val unit = bundlePrice / packCount

        if (packCount == 1) {
            return Analysis(bundlePrice, packCount, singlePrice, unit,
                null, null, Verdict.NOT_A_BUNDLE)
        }
        if (singlePrice == null) {
            return Analysis(bundlePrice, packCount, null, unit,
                null, null, Verdict.UNKNOWN)
        }

        val savings = singlePrice - unit
        val savingsPct = if (singlePrice > 0) (savings.toDouble() / singlePrice) * 100 else 0.0

        val verdict = when {
            savingsPct >= 30.0 -> Verdict.EXCEPTIONAL_DEAL
            savingsPct >= 5.0 -> Verdict.GOOD_DEAL
            savingsPct > -5.0 -> Verdict.NEUTRAL
            else -> Verdict.BAD_DEAL
        }

        return Analysis(
            bundlePrice = bundlePrice,
            packCount = packCount,
            singlePrice = singlePrice,
            unitPriceInBundle = unit,
            savingsPerUnit = savings,
            savingsPercent = savingsPct,
            verdict = verdict,
        )
    }
}
