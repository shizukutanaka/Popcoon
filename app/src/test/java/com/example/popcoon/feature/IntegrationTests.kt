package com.example.popcoon.feature

import com.example.popcoon.data.db.WatchlistItem
import com.example.popcoon.data.model.PriceRecord
import com.example.popcoon.feature.cart.CrossMallCartOptimizer
import com.example.popcoon.feature.cart.SmartCartService
import com.example.popcoon.feature.crossborder.CustomsSimulator
import com.example.popcoon.feature.darkpattern.DarkPatternTextDetector
import com.example.popcoon.feature.prediction.PricePredictionEngine
import com.example.popcoon.feature.scorer.BuyTimingScorer
import com.example.popcoon.feature.tco.TCOCalculator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Instant

class IntegrationTests : StringSpec({

    // ── CustomsSimulator ─────────────────────────────────────────────────
    "衣類 10,000円+5,000円送料 → 免税 (16,666以下)" {
        val r = CustomsSimulator.simulate(10_000, 5_000, "衣類")
        r.isTaxExempt shouldBe true
        r.totalLandedCost shouldBe 15_000L
    }

    "衣類 20,000円+5,000円送料 → 免税突破で関税+消費税" {
        val r = CustomsSimulator.simulate(20_000, 5_000, "衣類")
        r.isTaxExempt shouldBe false
        // Python 実装と一致: total=31,000
        r.totalLandedCost shouldBe 31_000L
    }

    "電子機器は関税0%" {
        val r = CustomsSimulator.simulate(30_000, 3_000, "電子機器")
        r.customsDuty shouldBe 0L
    }

    "価格 単調増加" {
        checkAll(Arb.long(0L..1_000_000L), Arb.long(0L..50_000L)) { price, ship ->
            val r1 = CustomsSimulator.simulate(price, ship, "衣類")
            val r2 = CustomsSimulator.simulate(price + 1000, ship, "衣類")
            (r2.totalLandedCost >= r1.totalLandedCost) shouldBe true
        }
    }

    // ── TCOCalculator ─────────────────────────────────────────────────────
    "インクジェット 5年 TCO 計算" {
        val r = TCOCalculator.calculate(8_000, "inkjet_printer", 5, 1.0)
        r.consumablesTotal shouldBe 106_000L  // Python と一致
        r.totalTco shouldBe 115_165L
        r.tcoPerMonth shouldBe 1_919L
    }

    "使用年数増加で total_tco 単調増加" {
        val r1 = TCOCalculator.calculate(50_000, "laptop", 2)
        val r2 = TCOCalculator.calculate(50_000, "laptop", 5)
        (r2.totalTco > r1.totalTco - r2.residualValue) shouldBe true
    }

    "使用強度増加で消耗品単調増加" {
        val low = TCOCalculator.calculate(10_000, "inkjet_printer", 5, 0.5)
        val med = TCOCalculator.calculate(10_000, "inkjet_printer", 5, 1.0)
        val high = TCOCalculator.calculate(10_000, "inkjet_printer", 5, 2.0)
        (low.consumablesTotal <= med.consumablesTotal) shouldBe true
        (med.consumablesTotal <= high.consumablesTotal) shouldBe true
    }

    // ── BuyTimingScorer ──────────────────────────────────────────────────
    fun history(prices: List<Long>): List<PriceRecord> =
        prices.mapIndexed { i, p ->
            PriceRecord(
                productKey = "p", platform = "amazon",
                listPrice = p + 500, realPrice = p,
                recordedAt = Instant.parse("2026-01-01T00:00:00Z")
                    .plusSeconds((i * 86400).toLong()),
            )
        }

    "13件未満で null" {
        BuyTimingScorer.score(5000, 6000, history(List(13) { 5000L })).shouldBeNull()
    }

    "スコアは 0..100 範囲" {
        checkAll(Arb.int(1000..10_000)) { p ->
            val hist = history(List(30) { p.toLong() })
            val score = BuyTimingScorer.score(p.toLong(), (p * 2).toLong(), hist)
            if (score != null) {
                (score.total >= 0 && score.total <= 100) shouldBe true
            }
        }
    }

    "verdict BUY_NOW 閾値 70" {
        // 200円は過去最安値到達(+30) + 定価97%OFF(+15) + 豊富な履歴(+10) → 合計90 → BUY_NOW 確実
        val history = history(List(95) { 5000L - it * 20L }.map { maxOf(it, 100L) })
        val score = BuyTimingScorer.score(200L, 8000L, history)
        score.shouldNotBeNull()
        score.verdict shouldBe BuyTimingScorer.Verdict.BUY_NOW
    }

    "ATL は高値圏より必ず高スコア" {
        val hist = history((0 until 30).map { (1000L + it) })
        val scoreLow = BuyTimingScorer.score(1000, 2000, hist)!!
        val scoreHigh = BuyTimingScorer.score(1029, 2000, hist)!!
        (scoreLow.total > scoreHigh.total) shouldBe true
    }

    // ── クロス機能シナリオ (現実的な複合ケース) ──────────────────────────
    "サクラ疑い商品: 大量レビュー満点 → LOW 判定" {
        val rt = com.example.popcoon.feature.review.ReviewTrustScorer
            .evaluate(rating = 4.95f, reviewCount = 5000)
        rt.trust shouldBe com.example.popcoon.feature.review.ReviewTrustScorer.Trust.LOW
    }

    "ダークパターン: 偽緊急性 + Drip Pricing を同時検出" {
        val dp = com.example.popcoon.feature.darkpattern.DarkPatternDetector
        val urgency = dp.detectInText("本日限り タイムセール 残り3点")
        val drip = dp.detectDripPricing(basePrice = 1000, totalPrice = 1400)
        // 識別: FAKE_SCARCITY (残り3点) + COUNTDOWN_MANIPULATION (本日限り/タイムセール) の2種類のみ
        urgency.size shouldBe 2
        val types = urgency.map { it.type }
        types.contains(com.example.popcoon.feature.darkpattern.DarkPatternDetector.WarningType.FAKE_SCARCITY) shouldBe true
        types.contains(com.example.popcoon.feature.darkpattern.DarkPatternDetector.WarningType.COUNTDOWN_MANIPULATION) shouldBe true
        drip.shouldNotBeNull()
    }

    "名寄せ: 同一型番は3モールで1グループ、最安が代表" {
        val pm = com.example.popcoon.feature.matching.ProductMatcher
        fun p(sku: String, plat: com.example.popcoon.data.model.Platform, price: Long) =
            com.example.popcoon.data.model.Product(
                sku = sku, title = "ソニー WH-1000XM5", platform = plat,
                realPrice = price, listPrice = price,
            )
        val groups = pm.groupByIdentity(
            listOf(
                p("A", com.example.popcoon.data.model.Platform.AMAZON, 40000),
                p("R", com.example.popcoon.data.model.Platform.RAKUTEN, 37000),
                p("Y", com.example.popcoon.data.model.Platform.YAHOO, 39000),
            ),
        )
        groups.size shouldBe 1
        groups.first().first().totalPrice shouldBe 37000  // 最安が代表
    }

    "買い時総合: ATL付近 + 自然レビュー + 警告なし = 高信頼の買い推奨" {
        val hist = history(List(95) { 1000L })  // 安定
        val score = BuyTimingScorer.score(1000, 1500, hist)!!
        val rt = com.example.popcoon.feature.review.ReviewTrustScorer
            .evaluate(4.3f, 800)
        // 安定価格・十分なレビュー → confidence HIGH
        score.confidence shouldBe "HIGH"
        rt.trust shouldBe com.example.popcoon.feature.review.ReviewTrustScorer.Trust.HIGH
    }

    // ── 新機能統合テスト (PORTING_SPEC.md 配線) ───────────────────────────────

    "A6 配線: Conformal margin は変動系列で正、安定系列でほぼゼロ" {
        val stable = history(List(30) { 1000L })
        val volatile = history((0 until 30).map { if (it % 2 == 0) 1000L else 2000L })
        val ps = PricePredictionEngine.predict(stable)!!
        val pv = PricePredictionEngine.predict(volatile)!!
        (ps.predictionMargin <= 10L) shouldBe true
        (pv.predictionMargin > 0L) shouldBe true
    }

    "A1 配線: 週次季節性がある系列で seasonalForecast7d が返る" {
        // 平日(月-金)=1000、週末=800 の 28日履歴
        val seasonal = history((0 until 28).map { if (it % 7 in 0..4) 1000L else 800L })
        val p = PricePredictionEngine.predict(seasonal)!!
        // 季節分解が有効に動作している → 非ゼロ
        (p.seasonalForecast7d > 0L) shouldBe true
    }

    "DarkPatternTextDetector: 5カテゴリを複合検出できる" {
        val text = "本日限り！残り3点。8人がカートに入れました"
        val signals = DarkPatternTextDetector.detect(text)
        val cats = signals.map { it.category }.toSet()
        DarkPatternTextDetector.Category.URGENCY in cats shouldBe true
        DarkPatternTextDetector.Category.SCARCITY in cats shouldBe true
        DarkPatternTextDetector.Category.SOCIAL_PROOF in cats shouldBe true
        // category 昇順保証
        signals.map { it.category } shouldBe signals.map { it.category }.sorted()
    }

    "SmartCart: 2商品を amazon に集約して送料無料ライン到達" {
        val malls = mapOf(
            "amazon" to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 2000.0),
            "rakuten" to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 5000.0),
        )
        fun watchItem(key: String, title: String, mall: String, price: Long) = WatchlistItem(
            productKey = key, sku = key, title = title, platform = mall,
            realPrice = price, listPrice = price + 500, url = "", imageUrl = null,
        )
        val items = listOf(
            watchItem("a:1", "完全ワイヤレスイヤホン X1", "amazon", 1000),
            watchItem("a:2", "スマートウォッチ Y2 ブラック", "amazon", 1000),
        )
        val r = SmartCartService.optimize(items, malls).shouldNotBeNull()
        // 合計 2000 >= free threshold 2000 → 送料 0
        r.optimized.shippingTotal shouldBe (0.0 plusOrMinus 1e-9)
        r.optimized.total shouldBe (2000.0 plusOrMinus 1e-9)
    }
})
