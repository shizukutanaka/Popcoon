package io.github.shizukutanaka.popcoon.feature.crash

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * PrivacyCrashReporter.sanitizeStack() の純関数テスト。
 * コンテキスト不要で Android SDK なしに実行可能。
 */
class PrivacyCrashReporterTest : StringSpec({

    fun sanitize(s: String) = PrivacyCrashReporter.sanitizeStack(s)

    // ── メールアドレス ────────────────────────────────────────────────────────
    "メールアドレスを [email] に置換" {
        sanitize("user@example.com triggered crash") shouldNotContain "user@example.com"
        sanitize("user@example.com triggered crash") shouldContain "[email]"
    }

    // ── URL クエリパラメータ ──────────────────────────────────────────────────
    "単一クエリパラメータを [redacted] に置換" {
        val r = sanitize("GET /api?api_key=supersecret HTTP/1.1")
        r shouldNotContain "supersecret"
        r shouldContain "[redacted]"
    }

    "複数クエリパラメータをそれぞれ [redacted] に置換" {
        val r = sanitize("url?key=abc&secret=xyz&plain=visible")
        r shouldNotContain "abc"
        r shouldNotContain "xyz"
    }

    // ── AWS アクセスキー ──────────────────────────────────────────────────────
    "AWS アクセスキーを [aws-key] に置換" {
        val r = sanitize("AKIAIOSFODNN7EXAMPLE in stack trace")
        r shouldNotContain "AKIAIOSFODNN7EXAMPLE"
        r shouldContain "[aws-key]"
    }

    // ── Authorization ─────────────────────────────────────────────────────────
    "Bearer トークンを [redacted] に置換" {
        val r = sanitize("Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.secret")
        r shouldNotContain "eyJhbGciOiJSUzI1NiJ9"
        r shouldContain "[redacted]"
    }

    "Basic 認証を [redacted] に置換" {
        val r = sanitize("authorization: Basic dXNlcjpwYXNz")
        r shouldNotContain "dXNlcjpwYXNz"
        r shouldContain "[redacted]"
    }

    // ── API key / secret パターン ─────────────────────────────────────────────
    "api_key の値を [redacted] に置換" {
        val r = sanitize("""{"api_key":"MY_SECRET_VALUE"}""")
        r shouldNotContain "MY_SECRET_VALUE"
        r shouldContain "[redacted]"
    }

    "password の値を [redacted] に置換" {
        val r = sanitize("password=hunter2 in trace")
        r shouldNotContain "hunter2"
        r shouldContain "[redacted]"
    }

    // ── IPv4 ──────────────────────────────────────────────────────────────────
    "IPv4 アドレスを [ip] に置換" {
        val r = sanitize("connecting to 192.168.1.100")
        r shouldNotContain "192.168.1.100"
        r shouldContain "[ip]"
    }

    // ── 電話番号 ──────────────────────────────────────────────────────────────
    "国内電話番号を [tel] に置換" {
        val r = sanitize("called 090-1234-5678 from device")
        r shouldNotContain "090-1234-5678"
        r shouldContain "[tel]"
    }

    "国際電話番号を [tel] に置換" {
        val r = sanitize("+81-90-1234-5678")
        r shouldNotContain "90-1234-5678"
        r shouldContain "[tel]"
    }

    // ── Android ファイルパス ──────────────────────────────────────────────────
    "Android ファイルパスのユーザー名部分を置換" {
        val r = sanitize("at /data/user/0/com.example/files/username/private.db")
        r shouldNotContain "username"
        r shouldContain "/data/user/0/[pkg]/files/[user]"
    }

    "外部ストレージパスのユーザー名部分を置換" {
        val r = sanitize("/storage/emulated/0/username")
        r shouldNotContain "/storage/emulated/0/username"
        r shouldContain "/storage/emulated/[u]/[user]"
    }

    // ── 無害なテキストは変更しない ────────────────────────────────────────────
    "無害なスタックトレースは変更しない" {
        val normal = "java.lang.NullPointerException: null\n\tat com.example.Foo.bar(Foo.kt:42)"
        sanitize(normal) shouldBe normal
    }
})
