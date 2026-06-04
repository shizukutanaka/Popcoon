package com.example.popcoon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PopcoonLoggerTest : StringSpec({

    "Level.VERBOSE は最低優先度" {
        (PopcoonLogger.Level.VERBOSE.priority < PopcoonLogger.Level.ERROR.priority) shouldBe true
    }

    "Level は 5 段階" {
        PopcoonLogger.Level.entries.size shouldBe 5
    }

    "Level の順序: VERBOSE < DEBUG < INFO < WARN < ERROR" {
        val levels = PopcoonLogger.Level.entries.sortedBy { it.priority }
        levels.map { it.name } shouldBe listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR")
    }

    // PII サニタイズは private なので、公開 API 経由で間接テスト
    // (Log.v/d/i/w/e を呼んでもクラッシュしないことを確認)
    "v/d/i/w/e が例外なく実行できる" {
        // リリースビルドでは INFO 未満はフィルタされるが例外は投げない
        PopcoonLogger.v("test", "verbose message with email@test.com")
        PopcoonLogger.d("test", "debug with IP 192.168.1.1")
        PopcoonLogger.i("test", "info with tel +81-90-1234-5678")
        PopcoonLogger.w("test", "warn with URL ?token=secret&key=xxx")
        PopcoonLogger.e("test", "error", RuntimeException("test"))
    }

    "Any タグは simpleName に解決される" {
        // 内部で tag::class.java.simpleName を使用
        // String → "String"、object → クラス名
        // クラッシュしないことだけ確認
        PopcoonLogger.i(42, "integer tag")
        PopcoonLogger.i(listOf(1, 2), "list tag")
    }
})
