package io.github.shizukutanaka.popcoon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException

class ApiResultTest : StringSpec({

    "Success.getOrNull は値を返す" {
        val r: ApiResult<Int> = ApiResult.Success(42)
        r.getOrNull() shouldBe 42
    }

    "Failure.getOrNull は null" {
        val r: ApiResult<Int> = ApiResult.Failure(ApiError.Network())
        r.getOrNull() shouldBe null
    }

    "getOrDefault は Failure 時にデフォルト値" {
        val r: ApiResult<String> = ApiResult.Failure(ApiError.NotFound("商品"))
        r.getOrDefault("なし") shouldBe "なし"
    }

    "map は Success の中身を変換" {
        val r = ApiResult.Success(10).map { it * 2 }
        r.getOrNull() shouldBe 20
    }

    "map は Failure をそのまま伝搬" {
        val r: ApiResult<Int> = ApiResult.Failure(ApiError.RateLimit(30))
        val mapped = r.map { it * 2 }
        mapped.shouldBeInstanceOf<ApiResult.Failure>()
    }

    "onSuccess は Success 時のみ実行" {
        var called = false
        ApiResult.Success("ok").onSuccess { called = true }
        called shouldBe true
    }

    "onSuccess は Failure 時に実行しない" {
        var called = false
        ApiResult.Failure(ApiError.Unknown()).onSuccess { called = true }
        called shouldBe false
    }

    "onFailure は Failure 時のみ実行" {
        var errorType: ApiError? = null
        ApiResult.Failure(ApiError.AuthFailed("token")).onFailure { errorType = it }
        errorType.shouldBeInstanceOf<ApiError.AuthFailed>()
    }

    // エラー種別
    "Network.userMessage はネットワーク系メッセージ" {
        ApiError.Network().userMessage() shouldContain "ネットワーク"
    }

    "RateLimit.userMessage は待機系メッセージ" {
        ApiError.RateLimit(60).userMessage() shouldContain "時間"
    }

    "NotFound.userMessage にリソース名が含まれる" {
        ApiError.NotFound("プリンター").userMessage() shouldContain "プリンター"
    }

    "ParseFailed.userMessage はパース系メッセージ" {
        ApiError.ParseFailed().userMessage() shouldContain "解析"
    }

    // apiCall
    "apiCall は成功時 Success" {
        val r = apiCall { 1 + 1 }
        r.getOrNull() shouldBe 2
    }

    "apiCall は IOException を Network に変換" {
        val r = apiCall { throw IOException("timeout") }
        (r as ApiResult.Failure).error.shouldBeInstanceOf<ApiError.Network>()
    }

    "apiCall は未知例外を Unknown に変換" {
        val r = apiCall { throw IllegalStateException("bug") }
        (r as ApiResult.Failure).error.shouldBeInstanceOf<ApiError.Unknown>()
    }
})
