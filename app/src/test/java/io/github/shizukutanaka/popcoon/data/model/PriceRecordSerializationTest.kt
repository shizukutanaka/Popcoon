package io.github.shizukutanaka.popcoon.data.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * PriceRecord の JSON シリアライズテスト。
 *
 * 往復テスト (Kotlin→JSON→Kotlin) だけでは **wire format** が正しいかを保証しない:
 * encodeToString と decodeFromString が同じキー名を使う限り、フィールド名が
 * backend の snake_case 契約と乖離していても往復は成立するため。
 *
 * backend (Cloudflare Workers) は `product_key` / `list_price` / `real_price` を
 * snake_case で要求する。Kotlin 側が `productKey` / `listPrice` / `realPrice` を
 * 送ると backend が 400 を返し、受け取る JSON のデシリアライズが
 * MissingFieldException で失敗する — 全 `runCatching` が swallow して emptyList() を
 * 返すため、通知・履歴機能が完全に無音で停止する。
 * このクラスの中核価値は **ワイヤーフォーマット照合テスト** にある。
 */
class PriceRecordSerializationTest : StringSpec({

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val sample = PriceRecord(
        productKey = "amazon:B0TEST",
        platform = "amazon",
        listPrice = 4980,
        realPrice = 3980,
        recordedAt = Instant.parse("2026-06-09T12:34:56Z"),
    )

    // ── ワイヤーフォーマット: backend が要求する snake_case キーを出力する ───────────
    // 識別性: このテストは @SerialName アノテーションが欠落しても往復テストは通るが、
    // ここで出力 JSON の "product_key" 等を確認することで差し戻しをブロックする。
    "エンコード結果が snake_case キーを含む (backend POST 契約)" {
        val encoded = json.encodeToString(PriceRecord.serializer(), sample)
        encoded shouldContain "\"product_key\""
        encoded shouldContain "\"list_price\""
        encoded shouldContain "\"real_price\""
        encoded shouldContain "\"recorded_at\""
    }

    "エンコード結果に camelCase キーが出ない (backend が 400 を返す旧フォーマットでない)" {
        val encoded = json.encodeToString(PriceRecord.serializer(), sample)
        encoded shouldNotContain "\"productKey\""
        encoded shouldNotContain "\"listPrice\""
        encoded shouldNotContain "\"realPrice\""
    }

    // ── backend レスポンス (snake_case JSON) を正しくデシリアライズできる ──────────
    "backend が返す snake_case JSON をデシリアライズできる (GET /v1/history)" {
        val backendJson = """
            {
              "product_key": "amazon:B0TEST",
              "platform": "amazon",
              "list_price": 4980,
              "real_price": 3980,
              "recorded_at": "2026-06-09T12:34:56Z"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(PriceRecord.serializer(), backendJson)
        decoded shouldBe sample
    }

    // ── Kotlin→JSON→Kotlin 往復 ─────────────────────────────────────────────
    "PriceRecord は往復で等価" {
        val encoded = json.encodeToString(PriceRecord.serializer(), sample)
        val decoded = json.decodeFromString(PriceRecord.serializer(), encoded)
        decoded shouldBe sample
    }

    "recorded_at は ISO-8601 文字列として出力される" {
        val encoded = json.encodeToString(PriceRecord.serializer(), sample)
        encoded shouldContain "\"recorded_at\":\"2026-06-09T12:34:56Z\""
    }

    "InstantIso8601Serializer 単体往復" {
        val instant = Instant.parse("2025-12-31T23:59:59Z")
        val encoded = json.encodeToString(InstantIso8601Serializer, instant)
        json.decodeFromString(InstantIso8601Serializer, encoded) shouldBe instant
    }
})
