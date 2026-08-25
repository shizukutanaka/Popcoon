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
     * ウィジェットに載せる件数。書き込み側 [io.github.shizukutanaka.popcoon.widget.WidgetUpdater] と
     * 読み出し側 [io.github.shizukutanaka.popcoon.widget.PopcoonWidget] で **同じ値**でなければ
     * ならない (以前は双方に 3 がベタ書きされており、片方だけ変えると静かに食い違う)。
     */
    const val WIDGET_ITEM_LIMIT = 3

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

    /** [topForWidget] が判定に使う 3 項目。Room エンティティに依存させないための入れ物。 */
    data class Candidate(
        val realPrice: Long,
        val targetPrice: Long?,
        val addedPrice: Long,
    )

    /**
     * 追加時からの下落率 (%)。下落していれば正、上昇・基準不明・価格不明なら 0。
     *
     * `realPrice <= 0` は取得失敗を 0 円として記録した汚染レコードなので統計に入れない
     * (100% 下落として最上位に並んでしまう)。
     */
    fun dropPercent(c: Candidate): Int =
        if (c.addedPrice > 0 && c.realPrice > 0) {
            (((c.addedPrice - c.realPrice) * 100) / c.addedPrice).toInt().coerceAtLeast(0)
        } else {
            0
        }

    /**
     * ウィジェットに載せる上位 [limit] 件を **行動可能な順に**選ぶ。
     *
     * ウィジェットは項目ごとに verdict を出し、`PopcoonWidget` が BUY_NOW を緑で強調する。
     * つまり存在理由は「今どれを買うべきか」の一目確認である。にもかかわらず以前は
     * `items.take(3)` で **WatchlistDao の `ORDER BY addedAt DESC` のまま先頭 3 件**を
     * 表示していた。4 件以上ウォッチしていると、目標価格に到達した項目が
     * 「最近追加した 3 件」に押し出されてホーム画面から見えなくなる。
     *
     * 通知側 ([io.github.shizukutanaka.popcoon.worker.PriceSyncPlanner.plan]) は既に
     * 「目標到達 → 下落率」の順で優先度を付けており、**同じアプリの 2 つの通知面で
     * 方針が食い違っていた**。ここを揃える。
     *
     * 並びは BUY_NOW (目標到達 or 有意な下落) を先に、次に追加時からの下落率が大きい順。
     * 安定ソートなので同順位内は元の `addedAt DESC` を保つ (従来の挙動が残る)。
     */
    fun <T> topForWidget(items: List<T>, limit: Int, of: (T) -> Candidate): List<T> =
        items.sortedWith(
            compareBy<T> { if (forItem(of(it).realPrice, of(it).targetPrice, of(it).addedPrice) == BUY_NOW) 0 else 1 }
                .thenByDescending { dropPercent(of(it)) },
        ).take(limit.coerceAtLeast(0))
}
