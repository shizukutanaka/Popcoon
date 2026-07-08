package io.github.shizukutanaka.popcoon.data.network

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 署名生成。
 *
 * Amazon Product Advertising API 5.0 が要求する標準的な AWS SigV4 を実装。
 * 純 Kotlin / 標準ライブラリのみ (AWS SDK 依存なし)。
 *
 * 仕様: https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
 *
 * 設計理由:
 *  - AmazonPaApiClient から分離して責務を単一化 (300行→200行)
 *  - 単独でユニットテスト可能 (ネットワーク不要)
 *  - 他の AWS API (S3, DynamoDB) でも再利用可能
 */
internal class AwsSigV4Signer(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val service: String,
) {
    companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
    }

    data class SignedRequest(val authorizationHeader: String, val amzDate: String)

    /**
     * リクエストに署名する。
     * @return Authorization ヘッダー + x-amz-date ヘッダー値のペア
     */
    fun sign(
        method: String,
        path: String,
        payload: String,
        host: String,
        amzTarget: String,
        contentType: String = "application/json; charset=utf-8",
        contentEncoding: String = "amz-1.0",
    ): SignedRequest {
        val now = Date()
        val amzDate = formatAmzDate(now)
        val dateStamp = formatDateStamp(now)

        val payloadHash = sha256Hex(payload)

        // Canonical headers (小文字 key 昇順)
        val canonicalHeaders = buildString {
            append("content-encoding:$contentEncoding\n")
            append("content-type:$contentType\n")
            append("host:$host\n")
            append("x-amz-date:$amzDate\n")
            append("x-amz-target:$amzTarget\n")
        }
        val signedHeaders = "content-encoding;content-type;host;x-amz-date;x-amz-target"

        val canonicalRequest = buildString {
            append(method).append("\n")
            append(path).append("\n")
            append("").append("\n")  // query (空)
            append(canonicalHeaders).append("\n")
            append(signedHeaders).append("\n")
            append(payloadHash)
        }

        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM).append("\n")
            append(amzDate).append("\n")
            append(credentialScope).append("\n")
            append(sha256Hex(canonicalRequest))
        }

        val signingKey = deriveSigningKey(secretKey, dateStamp, region, service)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authHeader = buildString {
            append(ALGORITHM)
            append(" Credential=$accessKey/$credentialScope")
            append(", SignedHeaders=$signedHeaders")
            append(", Signature=$signature")
        }
        return SignedRequest(authHeader, amzDate)
    }

    private fun deriveSigningKey(
        secret: String, date: String, region: String, service: String,
    ): ByteArray {
        val kSecret = ("AWS4$secret").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, date)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String =
        hmacSha256(key, data).toHex()

    private fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun formatAmzDate(d: Date): String {
        val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(d)
    }

    private fun formatDateStamp(d: Date): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(d)
    }
}
