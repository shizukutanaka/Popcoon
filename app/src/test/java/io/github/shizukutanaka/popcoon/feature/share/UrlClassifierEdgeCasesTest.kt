package io.github.shizukutanaka.popcoon.feature.share

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * UrlClassifier のエッジケーステスト。
 * 同種ソフト調査: Pricey の中核体験は Share Intent の堅牢性に依存する。
 */
class UrlClassifierEdgeCasesTest : StringSpec({

    "Amazon モバイル URL: m.amazon.co.jp もマッチする" {
        // regex は amazon.co.jp をサブストリングとして検索するため m. prefix も一致する
        val result = UrlClassifier.classify(
            "https://m.amazon.co.jp/dp/B0CTEST9999"
        )
        result.shouldNotBeNull()
        result.platform shouldBe Platform.AMAZON
        result.sku shouldBe "B0CTEST9999"
    }

    "Amazon URL: www. なしでもマッチする" {
        val result = UrlClassifier.classify(
            "https://amazon.co.jp/dp/B0SMARTPHONE"
        )
        result.shouldNotBeNull()
        result.sku shouldBe "B0SMARTPHONE"
    }

    "楽天 URL: 末尾スラッシュなし" {
        val r = UrlClassifier.classify(
            "https://item.rakuten.co.jp/myshop/item-99"
        )
        r.shouldNotBeNull()
        r.sku shouldBe "myshop:item-99"
    }

    "楽天 URL: 末尾スラッシュあり" {
        val r = UrlClassifier.classify(
            "https://item.rakuten.co.jp/myshop/item-99/"
        )
        r.shouldNotBeNull()
        r.sku shouldBe "myshop:item-99"
    }

    "Yahoo URL: .html 拡張子は SKU に含まれない (double-.html regression)" {
        val r = UrlClassifier.classify(
            "https://store.shopping.yahoo.co.jp/teststore/abc-123.html"
        )
        r.shouldNotBeNull()
        r.platform shouldBe Platform.YAHOO
        r.sku shouldBe "teststore:abc-123"
        r.canonicalUrl shouldBe "https://store.shopping.yahoo.co.jp/teststore/abc-123.html"
    }

    "Yahoo URL: 拡張子なし商品コードもそのまま取得できる" {
        val r = UrlClassifier.classify(
            "https://store.shopping.yahoo.co.jp/myshop/item-456"
        )
        r.shouldNotBeNull()
        r.sku shouldBe "myshop:item-456"
        r.canonicalUrl shouldBe "https://store.shopping.yahoo.co.jp/myshop/item-456.html"
    }

    "Twitter共有テキスト: 商品名 + URL の混在" {
        val text = "この商品めっちゃ良いよ！\nhttps://www.amazon.co.jp/dp/B0TWITTER12 #ステマ"
        val url = UrlClassifier.extractUrl(text)
        url shouldBe "https://www.amazon.co.jp/dp/B0TWITTER12"

        val classified = UrlClassifier.classify(url!!)
        classified?.sku shouldBe "B0TWITTER12"
    }

    "LINE 共有テキスト: 改行と日本語混在" {
        val text = "ほら見て\nhttps://item.rakuten.co.jp/yamada/item-line"
        val url = UrlClassifier.extractUrl(text)
        url.shouldNotBeNull()
        UrlClassifier.classify(url)?.platform shouldBe Platform.RAKUTEN
    }

    "Property: ランダムな文字列で例外なし" {
        checkAll(Arb.string(0, 200)) { random ->
            UrlClassifier.classify(random)
            UrlClassifier.extractUrl(random)
        }
    }

    "ASIN は厳密に英数字10文字" {
        // 9文字 → マッチしない
        UrlClassifier.classify("https://www.amazon.co.jp/dp/B0SHORT99").shouldBeNull()
        // 11文字 → 先頭10文字で一致してマッチ
        val long = UrlClassifier.classify("https://www.amazon.co.jp/dp/B0TOOLONG12X")
        long?.sku?.length shouldBe 10
    }

    "ASIN は大文字 + 数字のみ" {
        // 小文字含む → 不一致
        UrlClassifier.classify("https://www.amazon.co.jp/dp/b0lower1234").shouldBeNull()
    }

    "extractUrl: 2049文字の URL は上限 2048 文字で切り詰められる" {
        val longUrl = "https://www.amazon.co.jp/dp/B0TEST1234/" + "x".repeat(2100)
        val extracted = UrlClassifier.extractUrl(longUrl)
        // 上限 2048 文字: 先頭 "https://..." から最大 2048 文字の \S+ にマッチ
        extracted?.length shouldBe 2048
    }

    "extractUrl: URL なしテキストは null (raw-text フォールバックなし)" {
        UrlClassifier.extractUrl("これは商品説明です。URLはありません。").shouldBeNull()
        UrlClassifier.extractUrl("").shouldBeNull()
        UrlClassifier.extractUrl("ランダム文字列1234").shouldBeNull()
    }
})
