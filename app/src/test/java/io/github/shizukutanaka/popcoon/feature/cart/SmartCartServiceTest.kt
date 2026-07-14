package io.github.shizukutanaka.popcoon.feature.cart

import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import io.github.shizukutanaka.popcoon.feature.points.PointSimulator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * SmartCartService — WatchlistItem → CrossMallCartOptimizer パイプラインのテスト。
 */
class SmartCartServiceTest : StringSpec({

    fun item(
        key: String,
        title: String,
        platform: String,
        price: Long,
        listPrice: Long = price + 500,
    ) = WatchlistItem(
        productKey = key,
        sku = key,
        title = title,
        platform = platform,
        realPrice = price,
        listPrice = listPrice,
        url = "https://example.com/$key",
        imageUrl = null,
    )

    val noShipMalls = mapOf(
        "amazon"  to CrossMallCartOptimizer.MallConfig(),
        "rakuten" to CrossMallCartOptimizer.MallConfig(),
    )

    "空リストは null を返す" {
        SmartCartService.optimize(emptyList()).shouldBeNull()
    }

    "単一 item はそのプラットフォームに割り当て" {
        val items = listOf(item("a:001", "商品A", "amazon", 1000))
        val r = SmartCartService.optimize(items, noShipMalls).shouldNotBeNull()
        r.optimized.assignment.values shouldContainOnly listOf("amazon")
    }

    "異なる商品は独立した CartItem として扱われる" {
        val items = listOf(
            item("a:001", "全く異なる商品A ブランド1", "amazon", 1000),
            item("r:002", "全く異なる商品B ブランド2", "rakuten", 800),
        )
        val r = SmartCartService.optimize(items, noShipMalls).shouldNotBeNull()
        r.cartItems.size shouldBe 2
    }

    "送料最適化: amazon に集約して送料無料ライン到達" {
        val malls = mapOf(
            "amazon"  to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 2000.0),
            "rakuten" to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 5000.0),
        )
        // 別々の商品を2点ウォッチ — amazon に集約すると 2000 以上 → 送料ゼロ
        val items = listOf(
            item("a:iPhone", "iPhone 15 128GB SIMフリー スマートフォン", "amazon", 1000),
            item("a:AirPods", "AirPods Pro 第2世代 ノイズキャンセリング", "amazon", 1000),
        )
        val r = SmartCartService.optimize(items, malls).shouldNotBeNull()
        // 2000 >= 2000 free threshold → 送料 0
        r.optimized.shippingTotal shouldBe (0.0 plusOrMinus 1e-9)
        r.optimized.total shouldBe (2000.0 plusOrMinus 1e-9)
    }

    "savings は naiveTotal - optimized.total 以上" {
        val items = listOf(
            item("a:001", "商品X", "amazon", 1500),
            item("r:002", "商品Y", "rakuten", 1200),
        )
        val r = SmartCartService.optimize(items, noShipMalls).shouldNotBeNull()
        r.savingVsNaive shouldBe (r.naiveTotal - r.optimized.total plusOrMinus 1e-9)
    }

    // 識別テスト: 旧テストは noShipMalls で savings=0 の場合に 0>=0 を確認するだけ
    // (savingVsNaive が常に 0 でも緑)。
    // ここでは optimizer がモール集約で送料節約を実現するケースを具体値で固定する。
    //
    // 回帰 (機能過不足監査に関連する検証中に発見): 旧期待値 (36300/36000/300) は
    // PointSimulator.UserContext のデフォルト rakutenSpu=1 が「楽天商品は常に 1% 還元」を
    // 意味することを見落としていた (UserContext のデフォルト値を使うとこの optimize() 呼び出しも
    // 暗黙にそれを継承する)。さらに purchaseDate も未指定で LocalDate.now() に依存しており、
    // 実行日によっては「5と0のつく日」ボーナスが追加で乗り不安定になる潜在バグも併発していた。
    // どちらも一度も実 Kotlin コンパイル・実行で検証されておらず (本環境で今回初めて
    // throwaway harness で実行して発覚)、旧期待値は手計算のみで一度も実行確認されていなかった。
    // purchaseDate を固定 (5と0のつく日でない 2024-01-01) した上で、楽天 SPU 1% を反映した
    // 正しい値に修正する:
    //   Naive: WH は amazon で購入 (30000+500ship=30500)
    //        + ATH は rakuten (5000-50pt=4950 実質 +800ship=5750) = 36250
    //   Optimal: WH も rakuten へ移動 (30690実質+4950実質=35640 ≥ freeThreshold=35000 → 送料0) = 35640
    //   Savings = 36250 - 35640 = 610
    "クロスモール集約で送料節約: savingVsNaive == 610 (識別テスト、楽天SPU1%込み)" {
        val malls = mapOf(
            "amazon"  to CrossMallCartOptimizer.MallConfig(shipping = 500.0, freeThreshold = 50_000.0),
            "rakuten" to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 35_000.0),
        )
        val items = listOf(
            // WH-1000XM5: amazon 安値 (30000) だが rakuten にも在庫 (31000)
            item("a:WH", "ソニー WH-1000XM5 ワイヤレスヘッドホン", "amazon",  30_000),
            item("r:WH", "ソニー WH-1000XM5 ワイヤレスヘッドホン", "rakuten", 31_000),
            // ATH-M50x: 楽天専売 (5000)
            item("r:ATH", "Audio-Technica ATH-M50x ヘッドホン", "rakuten", 5_000),
        )
        val ctx = PointSimulator.UserContext(purchaseDate = java.time.LocalDate.of(2024, 1, 1))
        val r = SmartCartService.optimize(items, malls, ctx).shouldNotBeNull()
        r.naiveTotal shouldBe (36_250.0 plusOrMinus 1e-9)
        r.optimized.total shouldBe (35_640.0 plusOrMinus 1e-9)
        r.savingVsNaive shouldBe (610.0 plusOrMinus 1e-9)
    }

    "DEFAULT_MALL_CONFIGS はすべての主要3モールを含む" {
        SmartCartService.DEFAULT_MALL_CONFIGS.containsKey("amazon") shouldBe true
        SmartCartService.DEFAULT_MALL_CONFIGS.containsKey("rakuten") shouldBe true
        SmartCartService.DEFAULT_MALL_CONFIGS.containsKey("yahoo") shouldBe true
    }

    // ── Amazon Prime 会員は Amazon の送料が常に無料 (機能過不足監査で発見) ──────────
    // userCtx は既に PointSimulator (ポイント計算) に供給されていたが、mallConfigs (送料)
    // 側は常に静的な既定値のままで、Prime 会員でも Amazon の送料が課金される計算のままだった。
    "Amazon Prime 会員は送料無料ラインを満たさなくても Amazon 送料 0" {
        val malls = mapOf(
            "amazon" to CrossMallCartOptimizer.MallConfig(shipping = 500.0, freeThreshold = 50_000.0),
        )
        val items = listOf(item("a:001", "商品X", "amazon", 1_000))
        val prime = PointSimulator.UserContext(amazonPrime = true)
        val r = SmartCartService.optimize(items, malls, prime).shouldNotBeNull()
        r.optimized.shippingTotal shouldBe (0.0 plusOrMinus 1e-9)
        r.optimized.total shouldBe (1_000.0 plusOrMinus 1e-9)
        r.naiveTotal shouldBe (1_000.0 plusOrMinus 1e-9)
    }

    "Amazon Prime 会員でなければ従来どおり送料が課金される (回帰なし)" {
        val malls = mapOf(
            "amazon" to CrossMallCartOptimizer.MallConfig(shipping = 500.0, freeThreshold = 50_000.0),
        )
        val items = listOf(item("a:001", "商品X", "amazon", 1_000))
        val r = SmartCartService.optimize(items, malls, PointSimulator.UserContext(amazonPrime = false))
            .shouldNotBeNull()
        r.optimized.shippingTotal shouldBe (500.0 plusOrMinus 1e-9)
    }

    "Amazon Prime の送料無料は Amazon 以外のモールに波及しない" {
        val malls = mapOf(
            "rakuten" to CrossMallCartOptimizer.MallConfig(shipping = 800.0, freeThreshold = 50_000.0),
        )
        val items = listOf(item("r:001", "楽天専売品", "rakuten", 1_000))
        val prime = PointSimulator.UserContext(amazonPrime = true)
        val r = SmartCartService.optimize(items, malls, prime).shouldNotBeNull()
        r.optimized.shippingTotal shouldBe (800.0 plusOrMinus 1e-9)
    }
})
