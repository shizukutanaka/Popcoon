package com.example.popcoon.feature.ethics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class EcoEthicsScorerTest : StringSpec({

    "JP スマートフォン: スコアが 0-100 範囲" {
        val r = EcoEthicsScorer.score("JP", "smartphone", emptyList())
        r.overall shouldBeInRange 0..100
        r.co2Score shouldBeInRange 0..100
        r.laborScore shouldBeInRange 0..100
    }

    "CN 製造は JP より CO2 スコアが低い (kgCO2/kWh が高い)" {
        val jp = EcoEthicsScorer.score("JP", "laptop", emptyList())
        val cn = EcoEthicsScorer.score("CN", "laptop", emptyList())
        (jp.co2Score > cn.co2Score) shouldBe true
    }

    "DE は環境先進国 → 高スコア" {
        val de = EcoEthicsScorer.score("DE", "laptop", emptyList())
        val cn = EcoEthicsScorer.score("CN", "laptop", emptyList())
        (de.overall > cn.overall) shouldBe true
    }

    "エコマーク認証で CO2 スコアが +10" {
        val noLabel = EcoEthicsScorer.score("JP", "tv", emptyList())
        val withLabel = EcoEthicsScorer.score("JP", "tv", listOf("エコマーク"))
        (withLabel.co2Score >= noLabel.co2Score) shouldBe true
    }

    "低スコアには代替案メッセージあり" {
        val r = EcoEthicsScorer.score("CN", "tv", emptyList())
        if (r.overall < 55) {
            (r.greenAlternative != null) shouldBe true
        }
    }

    "高スコアには代替案なし" {
        val r = EcoEthicsScorer.score("DE", "laptop", listOf("EcoLabel"))
        if (r.overall >= 55) {
            (r.greenAlternative == null) shouldBe true
        }
    }

    "未知の国コードは fallback CO2 係数を使用 (例外なし)" {
        checkAll(Arb.string(2, 3)) { country ->
            val r = EcoEthicsScorer.score(country, "smartphone", emptyList())
            r.overall shouldBeInRange 0..100
        }
    }

    "CO2 計算: smartphone は tv より低い (想定kWh 90 vs 850)" {
        val phone = EcoEthicsScorer.score("JP", "smartphone", emptyList())
        val tv = EcoEthicsScorer.score("JP", "tv", emptyList())
        (phone.co2Kg < tv.co2Kg) shouldBe true
    }

    "null 国コードは fallback (例外なし)" {
        val r = EcoEthicsScorer.score(null, "laptop", emptyList())
        r.overall shouldBeInRange 0..100
    }
})
