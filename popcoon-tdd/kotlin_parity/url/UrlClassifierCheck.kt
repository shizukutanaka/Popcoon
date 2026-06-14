package com.example.popcoon.feature.share

import com.example.popcoon.data.model.Platform

/**
 * Standalone execution check for UrlClassifier (share-intent URL -> Platform+SKU).
 * No Android SDK: UrlClassifier is a pure object depending only on the Platform enum.
 * Verifies the "2-tap" share flow against real-world Amazon/Rakuten/Yahoo URL formats.
 */
private var fails = 0

private fun check(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        println("MISMATCH [$name]: expected=$expected actual=$actual")
        fails++
    }
}

private fun cls(url: String) = UrlClassifier.classify(url)

fun main() {
    // ── Amazon ────────────────────────────────────────────────────────────
    cls("https://www.amazon.co.jp/dp/B08N5WRWNW").let {
        check("amazon /dp/ platform", Platform.AMAZON, it?.platform)
        check("amazon /dp/ sku", "B08N5WRWNW", it?.sku)
        check("amazon /dp/ canonical", "https://www.amazon.co.jp/dp/B08N5WRWNW", it?.canonicalUrl)
    }
    cls("https://www.amazon.co.jp/gp/product/B08N5WRWNW").let {
        check("amazon /gp/product/ sku", "B08N5WRWNW", it?.sku)
    }
    // SEO URL: product name segment + /dp/ + ASIN + trailing /ref and query.
    cls("https://www.amazon.co.jp/Some-Product-Title/dp/B08N5WRWNW/ref=sr_1_1?keywords=x").let {
        check("amazon SEO sku", "B08N5WRWNW", it?.sku)
        check("amazon SEO canonical", "https://www.amazon.co.jp/dp/B08N5WRWNW", it?.canonicalUrl)
    }
    // Language path /-/en/dp/.
    cls("https://www.amazon.co.jp/-/en/dp/B08N5WRWNW").let {
        check("amazon lang-path sku", "B08N5WRWNW", it?.sku)
    }
    // Query/fragment stripped before capture.
    cls("https://www.amazon.co.jp/dp/B08N5WRWNW?th=1#desc").let {
        check("amazon query-strip sku", "B08N5WRWNW", it?.sku)
    }

    // ── Rakuten ───────────────────────────────────────────────────────────
    cls("https://item.rakuten.co.jp/shop123/abc-456/").let {
        check("rakuten platform", Platform.RAKUTEN, it?.platform)
        check("rakuten sku", "shop123:abc-456", it?.sku)
        check("rakuten canonical", "https://item.rakuten.co.jp/shop123/abc-456/", it?.canonicalUrl)
    }
    cls("https://item.rakuten.co.jp/shop/item?scid=af").let {
        check("rakuten query sku", "shop:item", it?.sku)
    }

    // ── Yahoo ─────────────────────────────────────────────────────────────
    cls("https://store.shopping.yahoo.co.jp/mystore/code123.html").let {
        check("yahoo .html platform", Platform.YAHOO, it?.platform)
        check("yahoo .html sku", "mystore:code123", it?.sku)
        check("yahoo .html canonical", "https://store.shopping.yahoo.co.jp/mystore/code123.html", it?.canonicalUrl)
    }
    cls("https://store.shopping.yahoo.co.jp/mystore/code123").let {
        check("yahoo no-ext sku", "mystore:code123", it?.sku)
        check("yahoo no-ext canonical re-adds .html", "https://store.shopping.yahoo.co.jp/mystore/code123.html", it?.canonicalUrl)
    }

    // ── Non-matching ────────────────────────────────────────────────────────
    check("unknown host -> null", null, cls("https://example.com/foo/bar"))

    // ── extractUrl: rescue a URL embedded in shared text ──────────────────────
    check(
        "extract embedded url",
        "https://item.rakuten.co.jp/shop/item",
        UrlClassifier.extractUrl("気になる商品 https://item.rakuten.co.jp/shop/item よろしく"),
    )
    check("extract none -> null", null, UrlClassifier.extractUrl("no url in this text"))

    if (fails == 0) {
        println("URL CLASSIFIER: all assertions passed")
    } else {
        println("URL CLASSIFIER: $fails assertion(s) FAILED")
        kotlin.system.exitProcess(1)
    }
}
