package io.github.shizukutanaka.popcoon.core

import io.github.shizukutanaka.popcoon.BuildConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BackendEndpointTest : StringSpec({
    val routes = listOf("/v1/history", "/v1/advice", "/v1/crash")

    "ビルド時に指定していないバックエンドは組み込まれない" {
        BuildConfig.BACKEND_URL shouldBe System.getenv("BACKEND_URL").orEmpty()
    }

    "未設定なら履歴・AI・クラッシュ送信の URL が存在しない" {
        routes.map { backendEndpoint("", it) } shouldBe listOf(null, null, null)
    }

    "空白だけの設定は未設定として扱う" {
        backendEndpoint(" \t ", "/v1/history") shouldBe null
    }

    "明示したサーバーのベースパスを保って各 API に接続できる" {
        routes.map { backendEndpoint(" https://user.example/popcoon/ ", it) } shouldBe
            routes.map { "https://user.example/popcoon$it" }
    }
})
