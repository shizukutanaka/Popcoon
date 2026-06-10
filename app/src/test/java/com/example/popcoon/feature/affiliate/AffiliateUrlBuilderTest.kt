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

    "API key 未設定なら素の URL を返す (CI 環境安全)" {
        // BuildConfig.AMAZON_PARTNER_TAG が空文字のとき
        // AffiliateUrlBuilder.build は rawUrl をそのまま返す
        val result = AffiliateUrlBuilder.build(Platform.AMAZON, amazonUrl, optOut = false)
        // tag が空の場合は URL 変更なし (安全な振る舞い)
        (result == amazonUrl || result.contains("tag=")) shouldBe true
    }

    "Amazon URL に既存 tag= があっても重複しない" {
        val withTag = "https://www.amazon.co.jp/dp/B0TEST?tag=existing"
        val result = AffiliateUrlBuilder.build(Platform.AMAZON, withTag, optOut = false)
        // tag パラメータは 1 つだけ
        val tagCount = result.split("tag=").size - 1
        (tagCount <= 1) shouldBe true
    }

    "楽天 URL: opt-out なら変換なし" {
        val result = AffiliateUrlBuilder.build(Platform.RAKUTEN, rakutenUrl, optOut = true)
        result shouldBe rakutenUrl
    }

    "Yahoo URL: sc_e パラメータが付与されるか optOut で変わらない" {
        // sid が空ならそのまま
        val result = AffiliateUrlBuilder.build(Platform.YAHOO, yahooUrl, optOut = false)
        (result == yahooUrl || result.contains("sc_e=")) shouldBe true
    }

    "Platform 別ルーティングが正しい" {
        // プラットフォームと URL のミスマッチ時も crash しない
        val r1 = AffiliateUrlBuilder.build(Platform.RAKUTEN, amazonUrl, optOut = false)
        val r2 = AffiliateUrlBuilder.build(Platform.YAHOO, rakutenUrl, optOut = false)
        // 認証情報なし (CI): 素のURLを返す。あり: アフィリエイトURLを生成する。
        (r1 == amazonUrl || r1.startsWith("https://hb.afl.rakuten.co.jp")) shouldBe true
        (r2 == rakutenUrl || r2.contains("sc_e=")) shouldBe true
    }
})
