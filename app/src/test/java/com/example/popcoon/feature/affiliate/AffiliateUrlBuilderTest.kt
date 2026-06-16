package com.example.popcoon.feature.affiliate

import com.example.popcoon.data.model.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * アフィリエイト URL 注入のテスト。
 *
 * 注意: BuildConfig の値が空文字の環境 (CI) では注入をスキップする。
 * これは intentional — API key なしでも UI が壊れないことを確認する。
 */
class AffiliateUrlBuilderTest : StringSpec({

    val amazonUrl = "https://www.amazon.co.jp/dp/B0TEST0001"
    val rakutenUrl = "https://item.rakuten.co.jp/shop/item-123/"
    val yahooUrl = "https://store.shopping.yahoo.co.jp/store/abc.html"

    "opt-out なら素の URL を返す" {
        AffiliateUrlBuilder.build(Platform.AMAZON, amazonUrl, optOut = true) shouldBe amazonUrl
        AffiliateUrlBuilder.build(Platform.RAKUTEN, rakutenUrl, optOut = true) shouldBe rakutenUrl
        AffiliateUrlBuilder.build(Platform.YAHOO, yahooUrl, optOut = true) shouldBe yahooUrl
    }

    // CI では BuildConfig.AMAZON_PARTNER_TAG = "" (環境変数未設定) → buildAmazon が rawUrl を
    // 即時返却 (Uri.parse を呼ばない)。従来の「result == url || contains("tag=")」は
    // 後半ブランチが CI で死蔵しており、任意の URL でも片方が真になる可能性があった。
    "API key 未設定なら Amazon は素の URL を返す (CI 環境)" {
        val result = AffiliateUrlBuilder.build(Platform.AMAZON, amazonUrl, optOut = false)
        result shouldBe amazonUrl
    }

    "API key 未設定なら既存 tag= 付き URL もそのまま返す (重複 tag の恐れなし)" {
        val withTag = "https://www.amazon.co.jp/dp/B0TEST?tag=existing"
        val result = AffiliateUrlBuilder.build(Platform.AMAZON, withTag, optOut = false)
        // tag 未設定 → rawUrl 即時返却: 既存 tag はそのまま保持、重複追加なし
        result shouldBe withTag
    }

    "楽天 URL: opt-out なら変換なし" {
        val result = AffiliateUrlBuilder.build(Platform.RAKUTEN, rakutenUrl, optOut = true)
        result shouldBe rakutenUrl
    }

    "API key 未設定なら Yahoo は素の URL を返す (CI 環境)" {
        val result = AffiliateUrlBuilder.build(Platform.YAHOO, yahooUrl, optOut = false)
        result shouldBe yahooUrl
    }

    "API key 未設定なら楽天は素の URL を返す (CI 環境: RAKUTEN_AFFILIATE_ID = 空)" {
        val r1 = AffiliateUrlBuilder.build(Platform.RAKUTEN, amazonUrl, optOut = false)
        val r2 = AffiliateUrlBuilder.build(Platform.YAHOO, rakutenUrl, optOut = false)
        r1 shouldBe amazonUrl
        r2 shouldBe rakutenUrl
    }
})
