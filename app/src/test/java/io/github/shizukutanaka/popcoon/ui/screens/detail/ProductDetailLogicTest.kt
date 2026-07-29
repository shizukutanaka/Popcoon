package io.github.shizukutanaka.popcoon.ui.screens.detail

import io.github.shizukutanaka.popcoon.feature.prediction.PricePredictionEngine
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText

/**
 * ProductDetailViewModel から切り出した純ロジックのテスト。
 * ViewModel 本体は Context 依存で plain JVM テスト不可のため、分岐が濃い部分だけを
 * ProductDetailLogic に分離して検証する (機能過不足監査: ProductDetailViewModel は
 * テストカバレッジゼロだった)。
 */
class ProductDetailLogicTest : StringSpec({

    fun prediction(current: Long, pred30: Long) = PricePredictionEngine.Prediction(
        currentPrice = current,
        predicted7d = current,
        predicted30d = pred30,
        buyNowProbability = 0.5f,
        historicLow = current,
        historicHigh = current,
        confidence = PricePredictionEngine.Confidence.MEDIUM,
    )

    // ── isValidProductKey ────────────────────────────────────────────────
    "productKey 検証: platform:sku 形式は有効" {
        ProductDetailLogic.isValidProductKey("amazon:B0ABC123") shouldBe true
    }

    "productKey 検証: 空文字・空白のみは無効" {
        ProductDetailLogic.isValidProductKey("") shouldBe false
        ProductDetailLogic.isValidProductKey("   ") shouldBe false
    }

    "productKey 検証: コロンなしは無効" {
        ProductDetailLogic.isValidProductKey("B0ABC123") shouldBe false
    }

    "productKey 検証: 片側が空なら無効 (platform 欠落 / sku 欠落)" {
        ProductDetailLogic.isValidProductKey(":B0ABC123") shouldBe false
        ProductDetailLogic.isValidProductKey("amazon:") shouldBe false
        ProductDetailLogic.isValidProductKey(":") shouldBe false
    }

    "productKey 検証: sku 内のコロンは許容 (limit=2 で分割)" {
        // URL 由来の sku にコロンが含まれるケースを弾かない
        ProductDetailLogic.isValidProductKey("rakuten:shop:item123") shouldBe true
    }

    // ── extractCertifications ────────────────────────────────────────────
    "エコ認証抽出: 「エコ」を含めばエコマーク" {
        ProductDetailLogic.extractCertifications("エコ洗剤 詰替") shouldContain "エコマーク"
    }

    "エコ認証抽出: green / オーガニック は green" {
        ProductDetailLogic.extractCertifications("Green Tea Organic") shouldContain "green"
        ProductDetailLogic.extractCertifications("オーガニックコットン") shouldContain "green"
    }

    "エコ認証抽出: green は大文字小文字を区別しない" {
        ProductDetailLogic.extractCertifications("GREEN PRODUCT") shouldContain "green"
    }

    "エコ認証抽出: 該当語なしは空リスト" {
        ProductDetailLogic.extractCertifications("ソニー WH-1000XM5 ヘッドホン") shouldBe emptyList()
    }

    "エコ認証抽出: 両方該当すれば 2 件" {
        ProductDetailLogic.extractCertifications("エコ オーガニック タオル").size shouldBe 2
    }

    // ── predictionContext ────────────────────────────────────────────────
    "予測コンテキスト: null 予測は空文字 (AI 助言に余計な文脈を渡さない)" {
        ProductDetailLogic.predictionContext(null) shouldBe ""
    }

    "予測コンテキスト: 30日後 < 現在価格 なら下降傾向" {
        ProductDetailLogic.predictionContext(prediction(10_000, 9_000)) shouldContainText "下降傾向"
    }

    "予測コンテキスト: 30日後 > 現在価格 なら上昇傾向" {
        ProductDetailLogic.predictionContext(prediction(10_000, 11_000)) shouldContainText "上昇傾向"
    }

    "予測コンテキスト: 同額なら横ばい (境界)" {
        ProductDetailLogic.predictionContext(prediction(10_000, 10_000)) shouldContainText "横ばい"
    }

    "予測コンテキスト: 30日後価格を含む (PricePredictionCard と矛盾しないため)" {
        val text = ProductDetailLogic.predictionContext(prediction(10_000, 9_000))
        text shouldContainText "9,000"
    }
})
