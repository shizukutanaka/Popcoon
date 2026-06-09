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
})
