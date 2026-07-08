package io.github.shizukutanaka.popcoon.core

import java.io.IOException

/**
 * API 呼び出しの結果型。
 *
 * `runCatching` の Throwable は型情報を失う。
 * `ApiResult<T>` ではエラー種別を sealed class で明示し、
 * 呼び出し側が網羅的に処理できる (when ステートメントの caller-side check)。
 *
 * 設計原則:
 *  - ネットワーク失敗 / レート制限 / 認証失敗 / パース失敗 を区別
 *  - Throwable は ApiError に内包 (デバッグ用)
 *  - UI 側はメッセージ生成のみで分岐ロジック不要
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): ApiResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onFailure(action: (ApiError) -> Unit): ApiResult<T> {
        if (this is Failure) action(error)
        return this
    }

    fun getOrNull(): T? = (this as? Success)?.data
    fun getOrDefault(default: @UnsafeVariance T): T = getOrNull() ?: default
}

/** エラー種別 — UI でユーザー向けメッセージを生成する基準 */
sealed class ApiError(open val cause: Throwable? = null) {
    data class Network(override val cause: Throwable? = null) : ApiError(cause)
    data class RateLimit(val retryAfterSec: Int = 0) : ApiError()
    data class AuthFailed(val reason: String = "認証に失敗") : ApiError()
    data class NotFound(val resource: String) : ApiError()
    data class ParseFailed(override val cause: Throwable? = null) : ApiError(cause)
    data class Unknown(override val cause: Throwable? = null) : ApiError(cause)

    /** UI 表示用の日本語メッセージ */
    fun userMessage(): String = when (this) {
        is Network -> "ネットワーク接続を確認してください"
        is RateLimit -> "アクセスが多いため、少し時間を置いてからお試しください"
        is AuthFailed -> "認証エラー。設定を確認してください"
        is NotFound -> "$resource が見つかりませんでした"
        is ParseFailed -> "データ形式の解析に失敗しました"
        is Unknown -> "予期しないエラーが発生しました"
    }
}

/**
 * try ブロックを ApiResult でラップする拡張関数。
 * `runCatching` の代替として、エラー種別を自動判定する。
 */
inline fun <T> apiCall(block: () -> T): ApiResult<T> {
    return try {
        ApiResult.Success(block())
    } catch (e: IOException) {
        ApiResult.Failure(ApiError.Network(e))
    } catch (e: kotlinx.serialization.SerializationException) {
        ApiResult.Failure(ApiError.ParseFailed(e))
    } catch (e: Exception) {
        ApiResult.Failure(ApiError.Unknown(e))
    }
}

/** 非同期版 */
suspend inline fun <T> apiCallSuspend(crossinline block: suspend () -> T): ApiResult<T> {
    return try {
        ApiResult.Success(block())
    } catch (e: IOException) {
        ApiResult.Failure(ApiError.Network(e))
    } catch (e: kotlinx.serialization.SerializationException) {
        ApiResult.Failure(ApiError.ParseFailed(e))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e  // コルーチンのキャンセルは上位に伝播させる (捕捉してはならない)
    } catch (e: Exception) {
        ApiResult.Failure(ApiError.Unknown(e))
    }
}
