package com.example.popcoon.feature.settings

import kotlinx.coroutines.flow.Flow

/** ViewModel が依存するユーザー設定の読み取り専用インタフェース。テスト差し替え用。 */
interface IUserPreferences {
    val rakutenSpu: Flow<Int>
    val yahooPremium: Flow<Boolean>
    val paypaySoftbank: Flow<Boolean>
    val amazonPrime: Flow<Boolean>
}
