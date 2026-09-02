package io.github.shizukutanaka.popcoon.ui.screens.watchlist

import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.github.shizukutanaka.popcoon.feature.watchlist.WidgetVerdict

// WatchlistScreen.kt (Compose) から切り出した純関数。同居していると Compose 依存に
// 巻き込まれて実コンパイルも kotest (WatchlistBuyVerdictTest) も走らなかった。
// 同一パッケージなので呼び出し側は無変更。

/**
 * ウォッチリスト行の買い時バッジ用 Verdict を返す。
 *
 * 判定はホーム画面ウィジェットと同じ [WidgetVerdict]（テスト済み純関数・履歴/通信不要）を
 * 再利用し、「ウィジェットは買い時を出すのにアプリ内ウォッチリストは出さない」不整合を解消する。
 * NEUTRAL は視覚ノイズになるため null（バッジ非表示）を返し、BUY_NOW / WAIT のみ表示する。
 */
internal fun watchlistBuyVerdict(item: WatchlistItem): BuyTimingScorer.Verdict? =
    when (WidgetVerdict.forItem(item.realPrice, item.targetPrice, item.addedPrice)) {
        WidgetVerdict.BUY_NOW -> BuyTimingScorer.Verdict.BUY_NOW
        WidgetVerdict.WAIT -> BuyTimingScorer.Verdict.WAIT
        else -> null
    }
