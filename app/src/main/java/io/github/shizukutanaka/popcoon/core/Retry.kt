package io.github.shizukutanaka.popcoon.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * [block] を最大1回リトライする。
 *
 * 検索 API クライアント (Amazon/Rakuten/Yahoo) は単発失敗時に即座に空リストへ
 * フォールバックしており、一時的なネットワーク瞬断でもリトライされていなかった
 * (商用リリース監査で発見)。一方 [io.github.shizukutanaka.popcoon.data.repository.BackendClient]
 * の価格履歴 POST は最大3回・指数バックオフ (1/2/4秒) を持つが、検索はユーザーが
 * 同期的に結果を待つ操作のため、そこまで長いリトライは体感速度を損なう —
 * 単発の軽いリトライ (既定 300ms 遅延) に留める。
 *
 * CancellationException はリトライせず即座に再 throw する (コルーチンのキャンセルは
 * 上位に伝播させなければならない)。2回目も失敗した場合はその例外をそのまま
 * 呼び出し元に伝播させる — 既存の `runCatching { retryOnce { ... } }.onFailure { ... }`
 * パターンでそのまま捕捉できるよう、ここでは例外を握りつぶさない。
 */
suspend fun <T> retryOnce(delayMillis: Long = 300, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        delay(delayMillis)
        block()
    }
}
