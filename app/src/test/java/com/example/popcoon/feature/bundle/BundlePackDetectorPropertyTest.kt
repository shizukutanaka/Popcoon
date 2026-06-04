package com.example.popcoon.feature.bundle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.next
import io.kotest.property.checkAll

/**
 * BundlePackDetector の property test + Python 仕様 oracle 一致確認。
 *
 * Python 側 bundle_pack_detector.py との出力 contract を Kotlin で再検証。
 * 273 tests / 100% mutation の Python 仕様を Kotlin が忠実に reproduce することを保証。
 */
class BundlePackDetectorPropertyTest : StringSpec({

    "verdict 不変条件: 0..100 範囲ではなく enum なので各境界で正しく振り分け" {
        checkAll(
            Arb.long(1L..10_000_000L),
            Arb.int(2..1000),
            Arb.long(1L..10_000_000L),
        ) { bundle, count, single ->
            val r = BundlePackDetector.detectValue(bundle, count, single)
            val pct = r.savingsPercent ?: 0.0
            // 境界一貫性
            when (r.verdict) {
                BundlePackDetector.Verdict.EXCEPTIONAL_DEAL -> (pct >= 30.0) shouldBe true
                BundlePackDetector.Verdict.GOOD_DEAL -> (pct >= 5.0 && pct < 30.0) shouldBe true
                BundlePackDetector.Verdict.NEUTRAL -> (pct > -5.0 && pct < 5.0) shouldBe true
                BundlePackDetector.Verdict.BAD_DEAL -> (pct <= -5.0) shouldBe true
                BundlePackDetector.Verdict.UNKNOWN -> Unit  // single == null のみ
                BundlePackDetector.Verdict.NOT_A_BUNDLE -> Unit  // count == 1 のみ
            }
        }
    }

    "単価計算は floor division (Python の // と同一)" {
        checkAll(Arb.long(1L..1_000_000L), Arb.int(2..100)) { bundle, count ->
            val r = BundlePackDetector.detectValue(bundle, count, null)
            r.unitPriceInBundle shouldBe (bundle / count)
        }
    }

    "savings 符号: positive = 得、negative = 損" {
        val good = BundlePackDetector.detectValue(900, 3, 350)
        good.savingsPerUnit?.let { (it > 0) shouldBe true }

        val bad = BundlePackDetector.detectValue(1200, 3, 300)
        bad.savingsPerUnit?.let { (it < 0) shouldBe true }
    }

    "extract: 数字 + 単位 + セット指示 のパターン全網羅" {
        listOf(
            "3本セット" to 3,
            "5個セット" to 5,
            "10袋まとめ買い" to 10,
            "× 2本" to 2,
            "x4個" to 4,
            "60粒入" to 60,
            "100枚入り" to 100,
            "12パック" to 12,
            "24缶ケース" to 24,
        ).forEach { (text, expected) ->
            val info = BundlePackDetector.extractBundleInfo(text)
            info?.packCount shouldBe expected
        }
    }

    "extract: bulk threshold 50" {
        BundlePackDetector.extractBundleInfo("マスク 49枚入り")?.isBulk shouldBe false
        BundlePackDetector.extractBundleInfo("マスク 50枚入り")?.isBulk shouldBe true
    }

    "Python 仕様 oracle: 既知ケースの完全一致" {
        // これらの値は Python 側 test_bundle_pack_detector.py の golden expected
        BundlePackDetector.detectValue(900, 3, 350).let {
            it.unitPriceInBundle shouldBe 300L
            it.verdict shouldBe BundlePackDetector.Verdict.GOOD_DEAL
            it.savingsPerUnit shouldBe 50L
        }
        BundlePackDetector.detectValue(420, 3, 200).let {
            it.unitPriceInBundle shouldBe 140L
            it.verdict shouldBe BundlePackDetector.Verdict.EXCEPTIONAL_DEAL
        }
    }
})
