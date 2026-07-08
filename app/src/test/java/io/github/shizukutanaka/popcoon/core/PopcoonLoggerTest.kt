package io.github.shizukutanaka.popcoon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PopcoonLoggerTest : StringSpec({

    "Level.VERBOSE は最低優先度" {
        PopcoonLogger.Level.VERBOSE.priority shouldBeLessThan PopcoonLogger.Level.ERROR.priority
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

    // ── sanitize (秘密情報リダクション) ─────────────────────────────
    "マルチパラメータ URL の全ての値を伏せる" {
        val out = PopcoonLogger.sanitize(
            "GET https://api.example.com/x?AWSAccessKeyId=AKIAIOSFODNN7EXAMPLE" +
                "&Signature=abc123XYZ&q=test",
        )
        out shouldNotContain "AKIAIOSFODNN7EXAMPLE"
        out shouldNotContain "abc123XYZ"
        out shouldNotContain "q=test"
        out shouldContain "[redacted]"
    }

    "AWS アクセスキー ID はクエリ外でも伏せる" {
        val out = PopcoonLogger.sanitize("Credential=AKIAIOSFODNN7EXAMPLE/20240101/us-west-2")
        out shouldNotContain "AKIAIOSFODNN7EXAMPLE"
        out shouldContain "[aws-key]"
    }

    "Authorization: Bearer トークンを伏せる" {
        val out = PopcoonLogger.sanitize("Authorization: Bearer sk-ant-secrettoken123")
        out shouldNotContain "sk-ant-secrettoken123"
        out shouldContain "[redacted]"
    }

    "api_key= の値を伏せる" {
        val out = PopcoonLogger.sanitize("config api_key=supersecretvalue loaded")
        out shouldNotContain "supersecretvalue"
        out shouldContain "[redacted]"
    }

    "JSON 形式 \"secret\":\"v\" の値を伏せる" {
        val out = PopcoonLogger.sanitize("body {\"secret\":\"toplevelsecret\"}")
        out shouldNotContain "toplevelsecret"
        out shouldContain "[redacted]"
    }

    "接頭辞付きキー MY_SECRET_KEY= の値を伏せる" {
        val out = PopcoonLogger.sanitize("env MY_SECRET_KEY=leakme123")
        out shouldNotContain "leakme123"
    }

    "Authorization: Basic 資格情報を伏せる" {
        val out = PopcoonLogger.sanitize("Authorization: Basic dXNlcjpwYXNz")
        out shouldNotContain "dXNlcjpwYXNz"
        out shouldContain "[redacted]"
    }

    "email / ip / tel は従来どおり伏せる" {
        PopcoonLogger.sanitize("user foo@bar.com") shouldNotContain "foo@bar.com"
        PopcoonLogger.sanitize("ip 192.168.1.1") shouldNotContain "192.168.1.1"
        PopcoonLogger.sanitize("tel +81-90-1234-5678") shouldNotContain "1234-5678"
    }
})
