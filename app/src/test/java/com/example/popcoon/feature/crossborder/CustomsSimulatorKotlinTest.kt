package com.example.popcoon.feature.crossborder

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

class CustomsSimulatorKotlinTest : StringSpec({

    "靴は最高関税 30% (日本市場で高額輸入品)" {
        val r = CustomsSimulator.simulate(30_000, 3_000, "靴")
        r.isTaxExempt shouldBe false
        // duty = 33,000 * 0.30 = 9,900
        r.customsDuty shouldBe 9_900L
    }

    "電子機器は関税 0% (ITA 協定)" {
        val r = CustomsSimulator.simulate(50_000, 5_000, "電子機器")
        r.customsDuty shouldBe 0L
    }

    "免税閾値 16,666円: ぴったりなら免税" {
        val r = CustomsSimulator.simulate(10_000, 6_666, "衣類")
        r.isTaxExempt shouldBe true
        r.totalLandedCost shouldBe 16_666L
    }

    "免税閾値超過: 16,667円から課税" {
        val r = CustomsSimulator.simulate(10_000, 6_667, "衣類")
        r.isTaxExempt shouldBe false
    }

    "国内より高いと MORE_EXPENSIVE 判定" {
        val r = CustomsSimulator.simulate(30_000, 5_000, "衣類", japanBestPrice = 20_000)
        r.verdict shouldBe CustomsSimulator.Verdict.MORE_EXPENSIVE
    }

    "大幅に安い場合 CHEAPER" {
        val r = CustomsSimulator.simulate(10_000, 2_000, "電子機器", japanBestPrice = 50_000)
        r.verdict shouldBe CustomsSimulator.Verdict.CHEAPER
    }

    // 食品/化粧品の NOT_RECOMMENDED は「中途半端な節約」帯でのみ発火する (Python オラクル準拠)。
    // 入力 (20k+2k, 食品, 国内40k): dutiable=22,000>免税 → total=29,240、国内の 90% (36,000) 未満
    // かつ免税掘り出し物でもない → NOT_RECOMMENDED。期待値は popcoon_core で検証済み。
    "食品は中途半端な節約帯で NOT_RECOMMENDED (衛生・検疫リスク)" {
        val r = CustomsSimulator.simulate(20_000, 2_000, "食品", japanBestPrice = 40_000)
        r.verdict shouldBe CustomsSimulator.Verdict.NOT_RECOMMENDED
    }

    // 回帰防止: 食品でも免税級の掘り出し物 (国内の 70% 未満) は CHEAPER が勝つ。
    // 旧実装は食品を無条件 NOT_RECOMMENDED にしており Python と乖離していた。
    // 入力 (10k+2k, 食品, 国内50k): total=12,000 (免税) < 35,000 → CHEAPER。
    "食品でも免税級の掘り出し物は CHEAPER (Python オラクル一致)" {
        val r = CustomsSimulator.simulate(10_000, 2_000, "食品", japanBestPrice = 50_000)
        r.verdict shouldBe CustomsSimulator.Verdict.CHEAPER
    }

    "外国価格 0 でも例外なし" {
        val r = CustomsSimulator.simulate(0, 0, "電子機器")
        r.totalLandedCost shouldBe 0L
        r.isTaxExempt shouldBe true
    }

    "totalLandedCost は 0 以上の単調増加 (入力増加で cost 増加)" {
        checkAll(
            Arb.long(0L..1_000_000L),
            Arb.long(0L..100_000L),
        ) { price, ship ->
            val r1 = CustomsSimulator.simulate(price, ship, "電子機器")
            val r2 = CustomsSimulator.simulate(price + 1000, ship, "電子機器")
            (r2.totalLandedCost >= r1.totalLandedCost) shouldBe true
        }
    }

    "消費税は round-down (floor)" {
        // dutiable = 20,000, 電子機器 duty=0, ctax = 20,000 * 0.1 = 2,000
        val r = CustomsSimulator.simulate(15_000, 5_000, "電子機器")
        r.consumptionTax shouldBe 2_000L
    }
})
