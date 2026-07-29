package io.github.shizukutanaka.popcoon.ui.screens.detail

import io.github.shizukutanaka.popcoon.core.CurrencyFormatter
import io.github.shizukutanaka.popcoon.feature.prediction.PricePredictionEngine

/**
 * ProductDetailViewModel の純粋な意思決定/整形ロジック。
 *
 * ViewModel 本体は Context / 具象 DataStore / BillingManager 等に依存するため plain JVM
 * ユニットテストでインスタンス化できない。分岐が濃く回帰しやすい純ロジック (productKey 検証・
 * 予測トレンド整形・エコ認証抽出) をここに切り出し、単体テスト可能にする
 * (PriceSyncPlanner と同方針)。全て Context 非依存の純関数。
 */
object ProductDetailLogic {

    /** productKey の形式検証: "platform:sku" (空文字列・片側欠落は不可)。 */
    fun isValidProductKey(key: String): Boolean {
        if (key.isBlank()) return false
        val parts = key.split(":", limit = 2)
        return parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()
    }

    /** タイトルに含まれるエコ認証ワードを抽出 (CO2 スコアの加点判定に使う)。 */
    fun extractCertifications(title: String): List<String> {
        val out = mutableListOf<String>()
        if (title.contains("エコ")) out += "エコマーク"
        if (title.lowercase().contains("green") || title.contains("オーガニック")) out += "green"
        return out
    }

    /**
     * PricePredictionEngine の予測を BuyingAdvisor の userContext 用テキストに変換する。
     * AI の自然文助言が、同画面の PricePredictionCard の数値予測と矛盾しないようにする。
     * 30 日後予測と現在価格の大小で下降/上昇/横ばいを決める。
     */
    fun predictionContext(prediction: PricePredictionEngine.Prediction?): String {
        if (prediction == null) return ""
        val trend = when {
            prediction.predicted30d < prediction.currentPrice -> "下降傾向"
            prediction.predicted30d > prediction.currentPrice -> "上昇傾向"
            else -> "横ばい"
        }
        return "価格予測 (統計モデル): 30日後 ${CurrencyFormatter.yen(prediction.predicted30d)} ($trend)"
    }
}
