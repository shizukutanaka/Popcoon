package io.github.shizukutanaka.popcoon.ui.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ネットワーク状態を Flow で提供。
 *
 * オフライン時の UI 縮退:
 *  - 検索: キャッシュ結果のみ (エラーではなく "オフラインモード" 表示)
 *  - 価格チャート: ローカル PriceCache から表示
 *  - AI アドバイザー: 無効化 (API 呼び出し不可)
 *  - バーコードスキャン: 本体は有効 (ローカル処理)、結果検索はキャッシュのみ
 *
 * Apple HIG: オフライン時でもアプリは有用であるべき。完全に機能停止しない。
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Status { AVAILABLE, UNAVAILABLE, LOSING, LOST }

    val status: Flow<Status> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Status.AVAILABLE)
            }
            override fun onLosing(network: Network, maxMsToLive: Int) {
                trySend(Status.LOSING)
            }
            override fun onLost(network: Network) {
                trySend(Status.LOST)
            }
            override fun onUnavailable() {
                trySend(Status.UNAVAILABLE)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)

        // 初期値を送信
        val current = cm.activeNetwork?.let { net ->
            cm.getNetworkCapabilities(net)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
        trySend(if (current) Status.AVAILABLE else Status.UNAVAILABLE)

        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .conflate()

    fun isCurrentlyConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork?.let { net ->
            cm.getNetworkCapabilities(net)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
    }
}
