package io.github.shizukutanaka.popcoon.data.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * AWS Signature V4 の生成ロジックを検証。
 *
 * **既知応答テスト (下段) が本体**。以前このファイルは構造テストしか持っておらず
 * (「64 文字の hex か」「必須ヘッダーが揃っているか」「payload が違えば署名も違うか」)、
 * 署名鍵の 4 段 HMAC の順序や canonical request の組み立てを間違えても **全て通った**。
 * 誤った署名は形だけ正しいので、PA-API が 403 を返してスクレイピングへ黙って
 * 退化するまで誰も気付けない。sign() に時刻を注入できるようにして、
 * 固定入力に対する厳密な期待値を置けるようにした。
 *
 * 構造テスト群 (上段) は「secret が出力に漏れない」等の別観点として残す。
 */
class AwsSigV4SignerTest : StringSpec({

    val signer = AwsSigV4Signer(
        accessKey = "AKIAIOSFODNN7EXAMPLE",
        secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region = "us-west-2",
        service = "ProductAdvertisingAPI",
    )

    "Authorization ヘッダーに ALGORITHM が含まれる" {
        val signed = signer.sign(
            method = "POST",
            path = "/paapi5/searchitems",
            payload = """{"Keywords":"プリンター"}""",
            host = "webservices.amazon.co.jp",
            amzTarget = "com.amazon.paapi5.v1.ProductAdvertisingAPIv1.SearchItems",
        )
        signed.authorizationHeader shouldStartWith "AWS4-HMAC-SHA256"
    }

    "Credential フィールドに accessKey が含まれる" {
        val signed = signer.sign(
            method = "POST",
            path = "/paapi5/getitems",
            payload = """{"ItemIds":["B0EXAMPLE"]}""",
            host = "webservices.amazon.co.jp",
            amzTarget = "com.amazon.paapi5.v1.ProductAdvertisingAPIv1.GetItems",
        )
        signed.authorizationHeader shouldContain "AKIAIOSFODNN7EXAMPLE"
    }

    "secretKey は絶対に出力に含まれない" {
        val signed = signer.sign(
            method = "POST",
            path = "/test",
            payload = "test",
            host = "example.com",
            amzTarget = "x.y",
        )
        signed.authorizationHeader shouldNotContain "wJalrXUtnFEMI"
    }

    "SignedHeaders に必須 5 ヘッダーが全て含まれる" {
        val signed = signer.sign(
            method = "POST",
            path = "/test",
            payload = "{}",
            host = "h",
            amzTarget = "t",
        )
        val signedHeaders = signed.authorizationHeader
            .substringAfter("SignedHeaders=")
            .substringBefore(",")
        signedHeaders.split(";").toSet() shouldBe setOf(
            "content-encoding", "content-type", "host", "x-amz-date", "x-amz-target",
        )
    }

    "amzDate は yyyyMMdd'T'HHmmss'Z' 形式" {
        val signed = signer.sign(
            method = "POST",
            path = "/test",
            payload = "{}",
            host = "h",
            amzTarget = "t",
        )
        signed.amzDate.matches(Regex("""\d{8}T\d{6}Z""")) shouldBe true
    }

    "Signature は 64 文字の hex (HMAC-SHA256)" {
        val signed = signer.sign(
            method = "POST",
            path = "/test",
            payload = "{}",
            host = "h",
            amzTarget = "t",
        )
        val signature = signed.authorizationHeader
            .substringAfter("Signature=")
        signature.length shouldBe 64
        signature.matches(Regex("""[a-f0-9]+""")) shouldBe true
    }

    "異なる payload は異なる signature を生成" {
        val s1 = signer.sign("POST", "/test", "{}", "h", "t")
        val s2 = signer.sign("POST", "/test", """{"a":1}""", "h", "t")
        s1.authorizationHeader.substringAfter("Signature=") shouldNotBe
            s2.authorizationHeader.substringAfter("Signature=")
    }

    // ── 既知応答テスト ────────────────────────────────────────────────────
    // 期待値の出所:
    //  (a) 署名鍵導出は **AWS ドキュメントが公開している導出例** と同一入力・同一出力
    //      (secret=wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY, 20150830/us-east-1/iam)。
    //  (b) フル署名は Python の hmac/hashlib による **独立実装** で同じ正規化手順を
    //      組んで算出した値 (このリポジトリの Python オラクル ↔ Kotlin parity と同じ方法)。
    //  どちらも「実装の出力をそのまま貼った」ものではない — 実装を疑える期待値になっている。

    "署名鍵導出が AWS 公開ベクタと一致する" {
        val s = AwsSigV4Signer("unused", "unused", "us-east-1", "iam")
        val key = s.deriveSigningKey(
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20150830", "us-east-1", "iam",
        )
        with(s) { key.toHex() } shouldBe
            "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9"
    }

    "固定時刻でのフル Authorization ヘッダーが独立実装と一致する" {
        val fixed = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse("20260818T120000Z")!!
        val signed = signer.sign(
            method = "POST",
            path = "/paapi5/searchitems",
            payload = """{"Keywords":"printer"}""",
            host = "webservices.amazon.co.jp",
            amzTarget = "com.amazon.paapi5.v1.ProductAdvertisingAPIv1.SearchItems",
            now = fixed,
        )
        signed.amzDate shouldBe "20260818T120000Z"
        signed.authorizationHeader shouldBe (
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20260818/us-west-2/" +
                "ProductAdvertisingAPI/aws4_request, SignedHeaders=content-encoding;" +
                "content-type;host;x-amz-date;x-amz-target, Signature=" +
                "2c925743cd89096ea40117c491f4646f9a18919cd6d7eabd0ff847201dd386f5"
            )
    }

    // Byte は符号付きなので、%02x が負値を 2 桁で正しく出さないと署名全体が壊れる。
    "toHex が負のバイトを 2 桁 hex にする" {
        val s = AwsSigV4Signer("a", "b", "c", "d")
        with(s) { byteArrayOf(-1, 0, 15, -128).toHex() } shouldBe "ff000f80"
    }
})
