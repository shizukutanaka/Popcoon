package io.github.shizukutanaka.popcoon.feature.share

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class UrlClassifierTest : StringSpec({

    "Amazon /dp/ASIN を抽出" {
        val r = UrlClassifier.classify("https://www.amazon.co.jp/dp/B0CL12345A")
        r.shouldNotBeNull()
        r.platform shouldBe Platform.AMAZON
        r.sku shouldBe "B0CL12345A"
        r.canonicalUrl shouldBe "https://www.amazon.co.jp/dp/B0CL12345A"
    }

    "Amazon /商品名/dp/ASIN/?ref=xxxx (典型的な共有形式)" {
        val r = UrlClassifier.classify(
            "https://www.amazon.co.jp/Apple-Watch-MX1234A-A2092/dp/B07ZPKBL9V?ref=cm_sw_r"
        )
        r.shouldNotBeNull()
        r.sku shouldBe "B07ZPKBL9V"
    }

    "Amazon /gp/product/ASIN" {
        val r = UrlClassifier.classify("https://www.amazon.co.jp/gp/product/B08N5WRWNW")
        r.shouldNotBeNull()
        r.sku shouldBe "B08N5WRWNW"
    }

    "楽天 item URL" {
        val r = UrlClassifier.classify(
            "https://item.rakuten.co.jp/example-shop/item-123-abc/"
        )
        r.shouldNotBeNull()
        r.platform shouldBe Platform.RAKUTEN
        r.sku shouldBe "example-shop:item-123-abc"
    }

    "楽天 + クエリパラメータ削除" {
        val r = UrlClassifier.classify(
            "https://item.rakuten.co.jp/shop/item?scid=xxx&keyword=yyy"
        )
        r.shouldNotBeNull()
        r.canonicalUrl shouldNotContain "?"
    }

    "Yahoo!ショッピング store URL" {
        val r = UrlClassifier.classify(
            "https://store.shopping.yahoo.co.jp/teststore/abc-123.html"
        )
        r.shouldNotBeNull()
        r.platform shouldBe Platform.YAHOO
        r.sku shouldBe "teststore:abc-123"
    }

    "未対応 URL は null" {
        UrlClassifier.classify("https://example.com/product/123").shouldBeNull()
        UrlClassifier.classify("not a url").shouldBeNull()
        UrlClassifier.classify("").shouldBeNull()
    }

    "テキストから URL 抽出" {
        UrlClassifier.extractUrl(
            "この商品いいよ！ https://www.amazon.co.jp/dp/B0CTEST1234 おすすめ"
        ) shouldBe "https://www.amazon.co.jp/dp/B0CTEST1234"
    }

    "テキスト内に URL なし" {
        UrlClassifier.extractUrl("URL ありません").shouldBeNull()
    }

    "前後空白を許容" {
        val r = UrlClassifier.classify("  https://www.amazon.co.jp/dp/B0SPACE1234  ")
        r.shouldNotBeNull()
        r.sku shouldBe "B0SPACE1234"
    }
})
