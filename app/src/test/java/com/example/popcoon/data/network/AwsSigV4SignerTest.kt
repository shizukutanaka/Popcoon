package com.example.popcoon.data.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

/**
 * AWS Signature V4 の生成ロジックを純関数として検証。
 *
 * 観点:
 *  - 同じ入力なら (時刻が変わるので Authorization は変わるが) 構造は一定
 *  - secret key が漏れていない (出力に含まれない)
 *  - 必須ヘッダーが全て signed
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
})
