package io.github.shizukutanaka.popcoon.feature.share

import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import io.ktor.http.isRedirect
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 短縮URL (amzn.to, a.r10.to 等) をリダイレクト追跡で正規URLへ解決する。
 *
 * UrlClassifier.classify() は純関数でネットワークに触れないため、Amazon/楽天/Yahoo!の
 * 正規ドメインパターンにマッチしない URL は無条件で分類失敗になる。実際のネイティブアプリの
 * 「共有」ボタンは amzn.to/a.r10.to 等の短縮URLを頻繁に出力するため、共有インテントで
 * アプリを開いても無言で失敗していた (機能過不足監査で発見 — テストファイル自身が
 * 「中核体験は Share Intent の堅牢性に依存する」と明記していたにもかかわらず、
 * 短縮URLのテストケースが1件も無かった)。
 *
 * `UrlClassifier.classify()` が失敗した URL に対してのみ呼び出し、HEAD (失敗時は GET)
 * でリダイレクトチェーンを追跡し最終 URL を返す。追跡先も EC 各社の商品ページとは限らない
 * (ログイン画面・キャンペーンページ等) ため、呼び出し側で再度 classify() にかけて
 * 分類できなければ最終的に失敗として扱う。
 */
@Singleton
class ShortUrlResolver @Inject constructor() {

    private val client = HttpClient {
        // リダイレクトを自動追従させず、都度 Location ヘッダーを読んで手動でホップする —
        // ホップ数の上限 (MAX_HOPS) を実装側で制御するため。
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
    }

    /**
     * URL のリダイレクトチェーンを最大 [MAX_HOPS] 回まで追跡し、最終的な URL を返す。
     * リダイレクトが無い (最初から最終URL)・追跡失敗・ホップ数超過のいずれの場合も、
     * その時点で分かっている最新の URL を返す (呼び出し側が classify() を再試行する)。
     * ネットワーク到達不能等で最初のホップにすら失敗した場合のみ null。
     */
    suspend fun resolve(url: String): String? {
        var current = url
        repeat(MAX_HOPS) {
            val next = try {
                headOrGetLocation(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this, "短縮URL解決失敗: ${e.message}", e)
                return if (current == url) null else current
            }
            if (next == null || next == current) return current
            current = next
        }
        return current
    }

    private suspend fun headOrGetLocation(url: String): String? {
        val response = try {
            client.head(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 一部の短縮URLサービスは HEAD に正しく応答しない (405 等) — GET にフォールバック。
            client.get(url)
        }
        if (!response.status.isRedirect()) return null
        return response.headers[HttpHeaders.Location]
    }

    companion object {
        private const val MAX_HOPS = 5
    }
}
