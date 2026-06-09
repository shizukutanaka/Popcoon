package com.example.popcoon.data.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * PriceRecord の JSON シリアライズ往復テスト。
 * kotlinx.serialization は java.time.Instant の組み込みシリアライザを持たないため、
 * InstantIso8601Serializer で ISO-8601 文字列として直列化していることを保証する。
 */
class PriceRecordSerializationTest : StringSpec({

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    "PriceRecord は往復で等価" {
        val original = PriceRecord(
            productKey = "amazon:B0TEST",
            platform = "amazon",
            listPrice = 4980,
            realPrice = 3980,
            recordedAt = Instant.parse("2026-06-09T12:34:56Z"),
        )
        val encoded = json.encodeToString(PriceRecord.serializer(), original)
        val decoded = json.decodeFromString(PriceRecord.serializer(), encoded)
        decoded shouldBe original
    }

    "recorded_at は ISO-8601 文字列として出力される" {
        val record = PriceRecord(
            productKey = "k", platform = "rakuten",
            listPrice = 1000, realPrice = 900,
            recordedAt = Instant.parse("2026-01-02T03:04:05Z"),
        )
        val encoded = json.encodeToString(PriceRecord.serializer(), record)
        encoded shouldContain "\"recorded_at\":\"2026-01-02T03:04:05Z\""
    }

    "InstantIso8601Serializer 単体往復" {
        val instant = Instant.parse("2025-12-31T23:59:59Z")
        val encoded = json.encodeToString(InstantIso8601Serializer, instant)
        json.decodeFromString(InstantIso8601Serializer, encoded) shouldBe instant
    }
})
