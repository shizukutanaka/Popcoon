package com.example.popcoon.data.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/**
 * FallbackScraper.extractJsonString の regex 挙動を直接テスト。
 * ネットワーク不要の純関数テスト。
 */
class FallbackScraperRegexTest : StringSpec({

    val scraper = FallbackScraper()

    "通常の JSON-LD から name を抽出" {
        val json = """{"name":"テスト商品","price":"2980"}"""
        scraper.extractJsonString(json, "name") shouldBe "テスト商品"
    }

    "アポストロフィを含む値を正しく抽出 (regression: [^\"'\\\\] で途中切断されていた)" {
        val json = """{"name":"John's Store","price":"1500"}"""
        scraper.extractJsonString(json, "name") shouldBe "John's Store"
    }

    "バックスラッシュエスケープを含む値を正しく抽出" {
        val json = """{"description":"line1\\nline2"}"""
        scraper.extractJsonString(json, "description") shouldBe "line1\\nline2"
    }

    "エスケープされたダブルクォートを含む値を正しく抽出" {
        val json = """{"title":"商品名 \"限定版\""}"""
        scraper.extractJsonString(json, "title") shouldBe """商品名 \"限定版\""""
    }

    "存在しないキーは null を返す" {
        val json = """{"name":"テスト","price":"1000"}"""
        scraper.extractJsonString(json, "brand").shouldBeNull()
    }

    "空の JSON は null を返す" {
        scraper.extractJsonString("{}", "name").shouldBeNull()
    }

    "price フィールドを抽出" {
        val json = """{"@type":"Product","name":"商品","price":"4,980"}"""
        scraper.extractJsonString(json, "price") shouldBe "4,980"
    }

    // ── 数値 (引用符なし) price の抽出 (regression: schema.org/Google は "price": 1980 を使う) ──
    "引用符なし整数 price を数値フォールバックで抽出" {
        val json = """{"@type":"Product","name":"商品","offers":{"price":1980}}"""
        // 文字列マッチは外れる (引用符が無い)
        scraper.extractJsonString(json, "price").shouldBeNull()
        // 数値フォールバックが拾う
        scraper.extractJsonNumber(json, "price") shouldBe "1980"
    }

    "引用符なし小数 price を数値フォールバックで抽出 (Google 公式例 38.99)" {
        val json = """{"@type":"Offer","price":38.99,"priceCurrency":"USD"}"""
        scraper.extractJsonNumber(json, "price") shouldBe "38.99"
    }

    "引用符付き price は数値フォールバックにマッチしない (役割分担)" {
        val json = """{"price":"1980"}"""
        // 数値フォールバックは引用符付きを拾わない → 文字列抽出の領分
        scraper.extractJsonNumber(json, "price").shouldBeNull()
        scraper.extractJsonString(json, "price") shouldBe "1980"
    }

    "数値フォールバックは存在しないキーで null" {
        scraper.extractJsonNumber("""{"price":1980}""", "lowPrice").shouldBeNull()
    }
})
