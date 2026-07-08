package io.github.shizukutanaka.popcoon.feature.bundle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class BundlePackDetectorTest : StringSpec({

    "3本セット¥900、単品¥350 → 実質¥300、GOOD_DEAL" {
        val result = BundlePackDetector.detectValue(
            bundlePrice = 900, packCount = 3, singlePrice = 350,
        )
        result.unitPriceInBundle shouldBe 300L
        result.verdict shouldBe BundlePackDetector.Verdict.GOOD_DEAL
        result.savingsPerUnit shouldBe 50L
    }

    "セットの方が高い → BAD_DEAL" {
        val result = BundlePackDetector.detectValue(
            bundlePrice = 1200, packCount = 3, singlePrice = 300,
        )
        result.verdict shouldBe BundlePackDetector.Verdict.BAD_DEAL
    }

    "1個セット → NOT_A_BUNDLE" {
        val result = BundlePackDetector.detectValue(
            bundlePrice = 300, packCount = 1, singlePrice = 300,
        )
        result.verdict shouldBe BundlePackDetector.Verdict.NOT_A_BUNDLE
    }

    "単品価格不明 → UNKNOWN" {
        val result = BundlePackDetector.detectValue(
            bundlePrice = 1200, packCount = 3, singlePrice = null,
        )
        result.verdict shouldBe BundlePackDetector.Verdict.UNKNOWN
    }

    "0個 → IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            BundlePackDetector.detectValue(100, 0, 100)
        }
    }

    "負の価格 → IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            BundlePackDetector.detectValue(-100, 3, 100)
        }
    }

    "30% off は EXCEPTIONAL_DEAL" {
        val result = BundlePackDetector.detectValue(420, 3, 200)
        result.verdict shouldBe BundlePackDetector.Verdict.EXCEPTIONAL_DEAL
    }

    // ── Extract bundle info ──────────────────────────────────────────────
    "「3本セット」を検出" {
        val info = BundlePackDetector.extractBundleInfo("洗剤 詰替 3本セット")
        info.shouldNotBeNull()
        info.packCount shouldBe 3
    }

    "「50枚入り」でbulk判定" {
        val info = BundlePackDetector.extractBundleInfo("マスク 50枚入り")
        info.shouldNotBeNull()
        info.packCount shouldBe 50
        info.isBulk shouldBe true
    }

    "「10個まとめ買い」は bulk でない (境界外)" {
        val info = BundlePackDetector.extractBundleInfo("コーヒー 10個まとめ買い")
        info.shouldNotBeNull()
        info.packCount shouldBe 10
        info.isBulk shouldBe false
    }

    "セット情報なしは null" {
        BundlePackDetector.extractBundleInfo("普通のシャンプー").shouldBeNull()
        BundlePackDetector.extractBundleInfo("").shouldBeNull()
        BundlePackDetector.extractBundleInfo(null).shouldBeNull()
    }

    "「× 2本」も認識" {
        val info = BundlePackDetector.extractBundleInfo("ビタミンC 60粒 × 2本")
        info.shouldNotBeNull()
        info.packCount shouldBe 2
    }

    "「24缶ケース」" {
        val info = BundlePackDetector.extractBundleInfo("ビール 24缶ケース")
        info.shouldNotBeNull()
        info.packCount shouldBe 24
    }

    // ── 回帰: 全角数字 (日本語タイトルで頻出) も検出 — ASCII \\d の乖離を修正 ──
    "全角「３本セット」を検出" {
        val info = BundlePackDetector.extractBundleInfo("洗剤 詰替 ３本セット")
        info.shouldNotBeNull()
        info.packCount shouldBe 3
    }

    "全角「５０枚入り」で bulk 判定" {
        val info = BundlePackDetector.extractBundleInfo("マスク ５０枚入り")
        info.shouldNotBeNull()
        info.packCount shouldBe 50
        info.isBulk shouldBe true
    }

    "全角「２４缶ケース」" {
        val info = BundlePackDetector.extractBundleInfo("ビール ２４缶ケース")
        info.shouldNotBeNull()
        info.packCount shouldBe 24
    }
})
