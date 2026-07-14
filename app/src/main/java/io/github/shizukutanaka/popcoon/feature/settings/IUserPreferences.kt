package io.github.shizukutanaka.popcoon.feature.settings

import kotlinx.coroutines.flow.Flow

/** ViewModel が依存するユーザー設定の読み取り専用インタフェース。テスト差し替え用。 */
interface IUserPreferences {
    val rakutenSpu: Flow<Int>
    val yahooPremium: Flow<Boolean>
    val paypaySoftbank: Flow<Boolean>
    val amazonPrime: Flow<Boolean>

    /** EC 会員設定の案内バナーを既に閉じた/確認済みか。 */
    val ecPromptDismissed: Flow<Boolean>

    /** バナーを閉じる/設定へ進んだ操作を記録し、以後表示しない。 */
    suspend fun dismissEcPrompt()

    /** ウォッチリストの並べ替えモード（WatchlistSort.Mode の ordinal）。既定 0 = ADDED_DESC。 */
    val watchlistSortOrdinal: Flow<Int>

    suspend fun setWatchlistSort(ordinal: Int)
}
