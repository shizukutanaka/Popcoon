package io.github.shizukutanaka.popcoon.feature.watchlist

/**
 * ウィジェット表示用の軽量「買い時」判定（純関数）。
 *
 * 詳細画面の [io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer] は完全な価格履歴
 * （ATL 近接・トレンド・変動率など）を必要とするが、ホーム画面ウィジェットは
 * 履歴を持たず、ウォッチリスト項目（現在価格・目標価格・追加時価格）しか参照できない。
 *
 * そこでウィジェットでは「ユーザーがウォッチを始めてからの値動き」と「目標達成」だけを
 * 使った控えめな判定を行う。過大なシグナルを避けるため:
 *  - 目標価格に到達 → BUY_NOW（ユーザーが明示した条件の達成）
 *  - 追加時から有意に下落 → BUY_NOW
 *  - 追加時から有意に上昇 → WAIT
 *  - それ以外 → NEUTRAL
 *
 * 文字列は [io.github.shizukutanaka.popcoon.widget.PopcoonWidget] が解釈する verdict キーと一致させる。
 */
object WidgetVerdict {

    const val BUY_NOW = "BUY_NOW"
    const val WAIT = "WAIT"
    const val NEUTRAL = "NEUTRAL"

    /** 追加時からの値動きで判定を切り替える閾値（%）。 */
    const val SIGNIFICANT_MOVE_PERCENT = 5

    /**
     * @param realPrice    現在価格（円）。0 以下は判定不能 → NEUTRAL。
     * @param targetPrice  ユーザー設定の目標価格（円）。null/0 以下 = 未設定。
     * @param addedPrice   ウォッチ追加時の価格（円）。0 = 基準なし。
     */
    fun forItem(realPrice: Long, targetPrice: Long?, addedPrice: Long): String {
        if (realPrice <= 0) return NEUTRAL

        // 1. 目標価格到達は最優先（ユーザーが明示した買い時）。
        if (targetPrice != null && targetPrice > 0 && realPrice <= targetPrice) return BUY_NOW

        // 2. 追加時を基準とした値動き（基準が分かる場合のみ）。
        if (addedPrice > 0) {
            val deltaPercent = (realPrice - addedPrice) * 100 / addedPrice
            if (deltaPercent <= -SIGNIFICANT_MOVE_PERCENT) return BUY_NOW
            if (deltaPercent >= SIGNIFICANT_MOVE_PERCENT) return WAIT
        }

        return NEUTRAL
    }
}
