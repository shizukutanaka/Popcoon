package com.example.popcoon.feature.notification

/**
 * 価格アラート判定の純関数。
 *
 * 同種の価格追跡アプリ（CamelCamelCamel / Keepa / ShopSense / Pricewise 等、
 * GitHub 調査による）が普遍的に備える「希望価格（target price）到達通知」を
 * Popcoon に導入するためのコアロジック。
 *
 * 従来 Popcoon は相対値下がり率（MIN_DROP_PERCENT）でのみ通知していたが、
 * ユーザーが設定した目標価格に到達した場合は **率に関係なく** 通知すべき。
 * 目標到達通知は値下がり通知より優先度が高く、過剰通知抑制の上限にも
 * 原則として影響されない（ユーザーが明示的に求めた情報のため）。
 *
 * Android 非依存の純関数 → 単体テストで網羅検証できる。
 */
object PriceAlertEvaluator {

    enum class Kind {
        /** ユーザー設定の目標価格に到達（最優先） */
        TARGET_REACHED,

        /** 目標未設定だが有意な値下がり */
        PRICE_DROP,

        /** 通知不要 */
        NONE,
    }

    data class Alert(
        val kind: Kind,
        /** 前回比の値下がり率（%）。値上がり・横ばい時は 0。 */
        val dropPercent: Int,
    ) {
        val shouldNotify: Boolean get() = kind != Kind.NONE
    }

    private val NONE = Alert(Kind.NONE, 0)

    /**
     * @param previousPrice 前回同期時の価格（円）。0 以下なら比較基準なし。
     * @param latestPrice   今回取得した価格（円）。
     * @param targetPrice   ユーザー設定の目標価格（円）。null = 未設定。
     * @param minDropPercent 値下がり通知に必要な最小下落率（%）。ノイズ抑制用。
     */
    fun evaluate(
        previousPrice: Long,
        latestPrice: Long,
        targetPrice: Long?,
        minDropPercent: Int,
    ): Alert {
        if (latestPrice <= 0) return NONE

        val dropPercent =
            if (previousPrice > 0 && latestPrice < previousPrice) {
                ((previousPrice - latestPrice) * 100 / previousPrice).toInt()
            } else {
                0
            }

        // 1. 目標価格到達は最優先（率の大小・前回比に関係なく通知）。
        if (targetPrice != null && targetPrice > 0 && latestPrice <= targetPrice) {
            return Alert(Kind.TARGET_REACHED, dropPercent)
        }

        // 2. 目標未到達でも、有意な値下がりがあれば通知。
        // minDropPercent > 0 の guard は誤り: minDropPercent=0 ("全て通知") の場合に
        // 正当な値下がりが NONE になる。正しくは dropPercent > 0 で実際の下落を確認する。
        if (dropPercent > 0 && dropPercent >= minDropPercent) {
            return Alert(Kind.PRICE_DROP, dropPercent)
        }

        return NONE
    }
}
