package io.github.shizukutanaka.popcoon.worker

// WeeklyDigestWorker.kt (WorkManager / Hilt) から切り出した純関数。同居していると
// CoroutineWorker 依存に巻き込まれて実コンパイルも kotest (WeeklyDigestWorkerTest) も
// 走らなかった。同一パッケージなので呼び出し側は無変更。
object WeeklyDigestLogic {

    /**
 * 追加時価格より現在価格が低い商品の件数を返す純関数。
 *
 * 除外するもの:
 *  - addedPrice == 0 — v3 以前に登録された基準なしアイテム。
 *  - realPrice <= 0 — 取得失敗を 0 として記録してしまった汚染レコード。
 *    `realPrice < addedPrice` だけで判定すると 0 円は常に「値下がり」になり、
 *    ダイジェストの件数が実態より水増しされる (BuyTimingScorer と同じ ¥0 汚染。
 *    書き込み側は塞いだが既存 DB の行は残りうるため、読み出し側でも無視する)。
 */
fun dropCountFrom(pricesPairs: List<Pair<Long, Long>>): Int =
pricesPairs.count { (realPrice, addedPrice) ->
    addedPrice > 0 && realPrice > 0 && realPrice < addedPrice
}
}
