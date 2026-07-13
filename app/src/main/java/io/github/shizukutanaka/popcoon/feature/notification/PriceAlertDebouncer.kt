package io.github.shizukutanaka.popcoon.feature.notification

/**
 * PriceAlertEvaluator の判定を「1 同期サイクル遅延確認」でラップする純関数。
 *
 * 背景: PriceAlertEvaluator.evaluate() は単一の (previousPrice, latestPrice) 比較のみで
 * 即座に通知を発火する。瞬間的なスクレイピングエラー・一時的な価格表示バグで
 * latestPrice が実際より大幅に安く記録されると、無条件で誤った「値下がり通知」や
 * 「目標価格到達」が飛んでしまう (機能過不足監査で発見)。
 *
 * 対策: 通知に値する変化を検知しても即座には発火させず、その観測値を「要確認」として
 * 保留する。次回同期で **全く同じ価格が再現した場合のみ** 発火させる。一時的な誤読みは
 * 通常次回同期では別の値 (訂正された正しい値、または別の誤値) になるため、この確認で
 * ほぼ排除できる。本物の値下がり・セールは翌日も同じ価格が続くのが通例なので、
 * 通知が最大 1 同期サイクル (実運用では約1日) 遅れるだけで実質的に失われない。
 *
 * 状態は WatchlistItem.pendingPrice (nullable カラム) に永続化し、呼び出し側
 * (PriceSyncWorker) が [Resolution.resolvedPrice] / [Resolution.newPendingPrice] を
 * DB へ書き戻す。
 */
object PriceAlertDebouncer {

    data class Resolution(
        val alert: PriceAlertEvaluator.Alert,
        /** 今回の同期後に watchlist.realPrice へ書き戻すべき値。 */
        val resolvedPrice: Long,
        /** 今回の同期後に watchlist.pendingPrice へ書き戻すべき値 (null = 保留なし)。 */
        val newPendingPrice: Long?,
    )

    private val NO_ALERT = PriceAlertEvaluator.Alert(PriceAlertEvaluator.Kind.NONE, 0)

    /**
     * @param previousPrice 直近で確認済みの価格 (watchlist.realPrice)。保留中でも変化しない。
     * @param latestPrice 今回取得した価格。
     * @param targetPrice ユーザー設定の目標価格。null = 未設定。
     * @param minDropPercent 値下がり通知に必要な最小下落率 (%)。
     * @param pendingPrice 前回同期で「要確認」として保留された観測値。null = 保留なし。
     */
    fun resolve(
        previousPrice: Long,
        latestPrice: Long,
        targetPrice: Long?,
        minDropPercent: Int,
        pendingPrice: Long?,
    ): Resolution {
        // 異常値 (0以下) は evaluate() 側でも無視されるが、ここでも状態を一切動かさず
        // 保留中の観測値をそのまま維持する (異常値で確認待ちを誤って解除しない)。
        if (latestPrice <= 0) {
            return Resolution(NO_ALERT, previousPrice, pendingPrice)
        }

        if (pendingPrice != null && latestPrice == pendingPrice) {
            // 確認された: 保留していた観測値が今回も再現した。
            // 判定基準は保留開始前の previousPrice のまま (保留中は動かしていない)。
            val alert = PriceAlertEvaluator.evaluate(previousPrice, latestPrice, targetPrice, minDropPercent)
            return Resolution(alert, latestPrice, null)
        }

        // 保留と不一致 (保留なし、または今回の値が異なる) → 新規観測として判定。
        // 比較基準は常に previousPrice (保留中も動かしていない確定済みの値)。
        val wouldAlert = PriceAlertEvaluator.evaluate(previousPrice, latestPrice, targetPrice, minDropPercent)
        return if (wouldAlert.shouldNotify) {
            // 通知に値する変化 → 即座に発火させず、次回同期での再現を待つ。
            // previousPrice はまだ動かさない (次回も同じ基準で比較するため)。
            Resolution(NO_ALERT, previousPrice, latestPrice)
        } else {
            // 通知不要な変化 (値上がり・僅少変動・横ばい) → 基準を直ちに更新、保留もクリア。
            Resolution(NO_ALERT, latestPrice, null)
        }
    }
}
