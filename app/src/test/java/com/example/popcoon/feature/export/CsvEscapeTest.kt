package com.example.popcoon.feature.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * CSV エクスポートのエスケープロジックをテスト (生産コードを直接呼び出す)。
 * Context 不要な純粋ロジック部分のみを検証する。
 */
class CsvEscapeTest : StringSpec({

    "通常文字列はクォートで囲む" {
        "テスト商品".csvEscape() shouldBe "\"テスト商品\""
    }

    "ダブルクォートは二重化してエスケープ" {
        "品名\"特殊".csvEscape() shouldBe "\"品名\"\"特殊\""
    }

    "カンマを含む文字列は正しくクォート" {
        "楽天,Amazon".csvEscape() shouldBe "\"楽天,Amazon\""
    }

    "改行を含む文字列" {
        "行1\n行2".csvEscape() shouldBe "\"行1\n行2\""
    }

    "空文字列" {
        "".csvEscape() shouldBe "\"\""
    }

    "数字文字列" {
        "4901234567890".csvEscape() shouldBe "\"4901234567890\""
    }

    "CSV ヘッダー行の形式" {
        val header = listOf(
            "商品キー", "タイトル", "プラットフォーム",
            "記録日時(JST)", "表示価格(円)", "実売価格(円)"
        ).map { it.csvEscape() }.joinToString(",")

        header shouldStartWith "\"商品キー\""
        header shouldContain "\"実売価格(円)\""
        header.split(",").size shouldBe 6
    }

    "Amazon の ASIN をそのままキーとして使える" {
        "amazon:B0CTEST001".csvEscape() shouldBe "\"amazon:B0CTEST001\""
    }

    // ── CSV インジェクション対策 (数式起動文字の前置クォート) ──────────────────
    "= で始まる商品名は ' を前置 (数式実行を防止)" {
        "=HYPERLINK(\"evil\")".csvEscape() shouldStartWith "\"'="
    }

    "+ - @ で始まるフィールドも ' を前置" {
        "+1".csvEscape() shouldBe "\"'+1\""
        "-cmd".csvEscape() shouldBe "\"'-cmd\""
        "@SUM".csvEscape() shouldBe "\"'@SUM\""
    }

    "数式起動文字を含まない通常フィールドは前置しない" {
        "テスト商品".csvEscape() shouldBe "\"テスト商品\""
        "amazon:B0X".csvEscape() shouldBe "\"amazon:B0X\""
    }
})
