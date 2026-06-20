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
 * 目標到達は **エッジトリガ**（CamelCamelCamel / Keepa と同じ）: 価格が目標を
 * 「上→下」に跨いだ同期でのみ 1 回通知する。レベルトリガ（latest <= target で
 * 毎回通知）だと、価格が目標以下に留まる限り日次同期のたびに同じ通知が
 * 振動付きで再発火してしまう（PriceSyncWorker は日次・setOnlyAlertOnce 無し）。
 * 既に目標以下のまま更に有意に下落した場合は PRICE_DROP として拾う。
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

        // 1. 目標価格到達は最優先。ただし**エッジトリガ**: 目標を「初めて下回った」
        //    同期のみ通知し、前回も目標以下だった場合は再通知しない（毎日スパム防止）。
        //    previousPrice <= 0（基準なし＝初回観測）で既に目標以下なら 1 回だけ通知する。
        //    既に目標以下のまま更に下落した場合は下の PRICE_DROP 判定で拾う。
        if (targetPrice != null && targetPrice > 0 && latestPrice <= targetPrice) {
            val wasAlreadyAtOrBelowTarget = previousPrice in 1..targetPrice
            if (!wasAlreadyAtOrBelowTarget) {
                return Alert(Kind.TARGET_REACHED, dropPercent)
            }
            // フォールスルー: 既に目標以下 → TARGET は出さず値下がり判定へ。
        }

        // 2. 目標未到達でも、有意な値下がりがあれば通知。
        // dropPercent > 0: 整数切り捨て後に 1%以上の実下落があること (小数点未満を除外)。
        // minDropPercent=0 は「閾値なし (全て通知)」として機能する。
        if (dropPercent > 0 && dropPercent >= minDropPercent) {
            return Alert(Kind.PRICE_DROP, dropPercent)
        }

        return NONE
    }
}
