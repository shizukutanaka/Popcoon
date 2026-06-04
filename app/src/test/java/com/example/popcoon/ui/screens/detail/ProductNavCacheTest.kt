package com.example.popcoon.ui.screens.detail

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * ProductNavCache のテスト。
 *
 * 画面間 Product 受け渡しの正確性を保証する。
 * savedStateHandle の落とし穴を回避するための重要コンポーネント。
 */
class ProductNavCacheTest : StringSpec({

    fun product(sku: String) = Product(
        sku = sku,
        title = "テスト $sku",
        platform = Platform.AMAZON,
        realPrice = 1000,
        listPrice = 1500,
    )

    beforeTest { ProductNavCache.clear() }

    "put した Product を consume で取得できる" {
        val p = product("A1")
        ProductNavCache.put(p)
        ProductNavCache.consume(p.key) shouldBe p
    }

    "consume は1回限り — 2回目は null" {
        val p = product("A2")
        ProductNavCache.put(p)
        ProductNavCache.consume(p.key)
        ProductNavCache.consume(p.key).shouldBeNull()
    }

    "未登録キーの consume は null" {
        ProductNavCache.consume("amazon:UNKNOWN").shouldBeNull()
    }

    "複数 Product を独立して保持" {
        val p1 = product("A3")
        val p2 = product("A4")
        ProductNavCache.put(p1)
        ProductNavCache.put(p2)
        ProductNavCache.consume(p1.key) shouldBe p1
        ProductNavCache.consume(p2.key) shouldBe p2
    }

    "上限 20 件を超えると最古が削除される" {
        repeat(25) { i -> ProductNavCache.put(product("SKU$i")) }
        // 最初の数件は押し出されている
        ProductNavCache.consume("amazon:SKU0").shouldBeNull()
        // 最新は残っている
        ProductNavCache.consume("amazon:SKU24") shouldBe product("SKU24")
    }

    "clear で全削除" {
        ProductNavCache.put(product("A5"))
        ProductNavCache.clear()
        ProductNavCache.consume("amazon:A5").shouldBeNull()
    }
})
