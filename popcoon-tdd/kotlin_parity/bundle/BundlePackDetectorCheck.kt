package io.github.shizukutanaka.popcoon.feature.bundle

/**
 * Standalone execution check for BundlePackDetector (set-sale unit-price + deal verdict).
 * No Android SDK: BundlePackDetector is a self-contained pure object.
 *
 * Focus: Japanese product titles very commonly use FULL-WIDTH digits (３本セット),
 * but the regexes use ASCII \d. This check asserts full-width counts are detected
 * (the same Unicode class that broke DarkPatternTextDetector).
 */
private var fails = 0

private fun check(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        println("MISMATCH [$name]: expected=$expected actual=$actual")
        fails++
    }
}

private fun count(title: String) = BundlePackDetector.extractBundleInfo(title)?.packCount

fun main() {
    // ── ASCII digits (baseline) ────────────────────────────────────────────
    check("ascii 3本セット", 3, count("徳用 3本セット お買い得"))
    check("ascii 5個入り", 5, count("のど飴 5個入り"))
    check("ascii x3", 3, count("乾電池 ×3本 まとめ"))
    check("ascii 24本ケース", 24, count("水 24本 ケース"))

    // ── Full-width digits (common in JP titles) — must also be detected ─────
    check("zenkaku ３本セット", 3, count("徳用 ３本セット お買い得"))
    check("zenkaku ５個入り", 5, count("のど飴 ５個入り"))
    check("zenkaku ×３本", 3, count("乾電池 ×３本 まとめ"))
    check("zenkaku ２４本ケース", 24, count("水 ２４本 ケース"))

    // ── Non-bundle / no count ──────────────────────────────────────────────
    check("no bundle", null, count("普通の単品商品"))

    // ── detectValue verdicts (independent hand calc) ───────────────────────
    // bundle 1000 / 5 = 200 unit; single 300 -> savings 100 -> 33.3% -> EXCEPTIONAL
    BundlePackDetector.detectValue(1000, 5, 300).let {
        check("value unit", 200L, it.unitPriceInBundle)
        check("value verdict exceptional", BundlePackDetector.Verdict.EXCEPTIONAL_DEAL, it.verdict)
    }
    // unit 200 vs single 210 -> ~4.8% -> NEUTRAL (>=5 needed for GOOD)
    BundlePackDetector.detectValue(1000, 5, 210).let {
        check("value verdict neutral", BundlePackDetector.Verdict.NEUTRAL, it.verdict)
    }
    // packCount 1 -> NOT_A_BUNDLE
    check("value not-a-bundle", BundlePackDetector.Verdict.NOT_A_BUNDLE,
        BundlePackDetector.detectValue(500, 1, 500).verdict)
    // no single price -> UNKNOWN
    check("value unknown", BundlePackDetector.Verdict.UNKNOWN,
        BundlePackDetector.detectValue(1000, 5, null).verdict)

    if (fails == 0) {
        println("BUNDLE PACK DETECTOR: all assertions passed")
    } else {
        println("BUNDLE PACK DETECTOR: $fails assertion(s) FAILED")
        kotlin.system.exitProcess(1)
    }
}
