package com.example.popcoon.feature.ethics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class EcoEthicsScorerTest : StringSpec({

    // ── Python (popcoon_core.score_eco_ethics) との絶対値パリティ ──────────
    // 期待値は Python 実装を実行して取得。乖離したらどちらかが壊れている。
    "パリティ: JP smartphone []" {
        val r = EcoEthicsScorer.score("JP", "smartphone", emptyList())
        r.overall shouldBe 62
        r.co2Score shouldBe 45
        r.laborScore shouldBe 82
        r.co2Kg shouldBe (70.0 plusOrMinus 1e-6)
        r.greenAlternative.shouldBeNull()
    }

    "パリティ: CN smartphone []" {
        val r = EcoEthicsScorer.score("CN", "smartphone", emptyList())
        r.overall shouldBe 46
        r.co2Score shouldBe 25
        r.laborScore shouldBe 52
        r.co2Kg shouldBe (121.333333 plusOrMinus 1e-4)
        r.greenAlternative.shouldNotBeNull()
    }

    "パリティ: US laptop [green]" {
        val r = EcoEthicsScorer.score("US", "laptop", listOf("green"))
        r.overall shouldBe 72
        r.co2Score shouldBe 75
        r.laborScore shouldBe 78
    }

    "パリティ: VN tv [エコマーク]" {
        val r = EcoEthicsScorer.score("VN", "tv", listOf("エコマーク"))
        r.overall shouldBe 56
        r.co2Score shouldBe 55
        r.laborScore shouldBe 48
    }

    "パリティ: null unknown []" {
        val r = EcoEthicsScorer.score(null, "unknown", emptyList())
        r.overall shouldBe 54
        r.co2Score shouldBe 45
        r.laborScore shouldBe 55
        r.greenAlternative.shouldBeNull()
    }

    // ── 一般プロパティ ──────────────────────────────────────────────
    "JP スマートフォン: スコアが 0-100 範囲" {
        val r = EcoEthicsScorer.score("JP", "smartphone", emptyList())
        r.overall shouldBeInRange 0..100
        r.co2Score shouldBeInRange 0..100
        r.laborScore shouldBeInRange 0..100
    }

    "CN 製造は JP より CO2 スコアが低い (Python oracle: JP=45, CN=25)" {
        val jp = EcoEthicsScorer.score("JP", "laptop", emptyList())
        val cn = EcoEthicsScorer.score("CN", "laptop", emptyList())
        jp.co2Score shouldBe 45
        cn.co2Score shouldBe 25
    }

    "DE は環境先進国 → 高スコア (Python oracle: DE=77, CN=46)" {
        val de = EcoEthicsScorer.score("DE", "laptop", emptyList())
        val cn = EcoEthicsScorer.score("CN", "laptop", emptyList())
        de.overall shouldBe 77
        cn.overall shouldBe 46
    }

    // 回帰: 日本より低炭素な原産国 (DE 0.30 / US 0.38 < JP 0.45) には「国産代替で削減」を
    // 提示しない。以前は負の削減率 ("CO2-50%削減可") を表示する共有バグだった。
    "低炭素な原産国 (DE/US) には国産代替を提示しない (負の削減率バグ回帰)" {
        EcoEthicsScorer.score("DE", "laptop", emptyList()).greenAlternative.shouldBeNull()
        EcoEthicsScorer.score("US", "laptop", emptyList()).greenAlternative.shouldBeNull()
        // 高炭素国にはちゃんと提示し続ける (機能が無効化されていないこと)。
        EcoEthicsScorer.score("CN", "laptop", emptyList()).greenAlternative.shouldNotBeNull()
    }

    "エコマーク認証で CO2 スコアが +10 (Python oracle: bare=45 → 55)" {
        val noLabel = EcoEthicsScorer.score("JP", "tv", emptyList())
        val withLabel = EcoEthicsScorer.score("JP", "tv", listOf("エコマーク"))
        noLabel.co2Score shouldBe 45
        withLabel.co2Score shouldBe 55
    }

    // 代替案は「国産か否か」で決まる (スコアではない、Python と同仕様)
    "JP (国産) は代替案なし" {
        EcoEthicsScorer.score("JP", "laptop", emptyList()).greenAlternative.shouldBeNull()
    }

    // 高炭素国 (CO2係数 > JP=0.45) + 既知カテゴリ → 国産代替案を提示。
    // DEは低炭素 (0.30) なので提示されない (上の回帰テストを参照)。
    "非JP 高炭素国 (CN) + 既知カテゴリ は代替案あり" {
        EcoEthicsScorer.score("CN", "laptop", emptyList())
            .greenAlternative.shouldNotBeNull()
    }

    "未知カテゴリは代替案なし" {
        EcoEthicsScorer.score("CN", "unknown", emptyList()).greenAlternative.shouldBeNull()
    }

    "未知の国コードは fallback 係数を使用 (例外なし)" {
        checkAll(Arb.string(2, 3)) { country ->
            val r = EcoEthicsScorer.score(country, "smartphone", emptyList())
            r.overall shouldBeInRange 0..100
        }
    }

    "CO2 計算: smartphone は tv より低い (Python oracle: phone=70.0, tv=400.0)" {
        val phone = EcoEthicsScorer.score("JP", "smartphone", emptyList())
        val tv = EcoEthicsScorer.score("JP", "tv", emptyList())
        phone.co2Kg shouldBe (70.0 plusOrMinus 1e-6)
        tv.co2Kg shouldBe (400.0 plusOrMinus 1e-6)
    }

    "null 国コードは fallback (例外なし)" {
        val r = EcoEthicsScorer.score(null, "laptop", emptyList())
        r.overall shouldBeInRange 0..100
    }
})
