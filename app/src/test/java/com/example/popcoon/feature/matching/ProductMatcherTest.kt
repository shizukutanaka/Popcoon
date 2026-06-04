package com.example.popcoon.feature.matching

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan

class ProductMatcherTest : StringSpec({

    fun product(
        sku: String,
        title: String,
        platform: Platform = Platform.AMAZON,
        price: Long = 1000,
        jan: String? = null,
    ) = Product(
        sku = sku,
        title = title,
        platform = platform,
        realPrice = price,
        listPrice = price,
        janCode = jan,
    )

    // ── JAN コード一致 ────────────────────────────────────────────────────
    "JAN コードが一致すれば類似度 1.0" {
        val a = product("A1", "ソニー ヘッドホン", Platform.AMAZON, jan = "4548736112001")
        val b = product("R1", "SONY イヤホン 楽天", Platform.RAKUTEN, jan = "4548736112001")
        ProductMatcher.similarity(a, b) shouldBe 1.0
    }

    "JAN が異なれば 1.0 にならない" {
        val a = product("A1", "商品A", jan = "4548736112001")
        val b = product("R1", "商品B", jan = "4548736112002")
        ProductMatcher.similarity(a, b) shouldBeLessThan 1.0
    }

    // ── 型番マッチング ────────────────────────────────────────────────────
    "同じ型番 WH-1000XM5 で高類似度" {
        val a = product("A1", "ソニー ワイヤレスヘッドホン WH-1000XM5 ブラック")
        val b = product("R1", "SONY WH-1000XM5 黒 【送料無料】", Platform.RAKUTEN)
        ProductMatcher.similarity(a, b) shouldBeGreaterThanOrEqual 0.7
    }

    "型番抽出: WH-1000XM5" {
        ProductMatcher.extractModelNumber("ソニー WH-1000XM5 ブラック") shouldBe "WH1000XM5"
    }

    "型番抽出: RTX 4090" {
        ProductMatcher.extractModelNumber("GeForce RTX 4090 搭載") shouldBe "RTX4090"
    }

    "型番なしは null" {
        ProductMatcher.extractModelNumber("りんご 5個セット") shouldBe null
    }

    // ── タイトル正規化 ────────────────────────────────────────────────────
    "ノイズ語を除去" {
        val tokens = ProductMatcher.normalizeTitle("【送料無料】正規品 コーヒー豆 500g")
        tokens.contains("送料無料") shouldBe false
        tokens.contains("正規品") shouldBe false
    }

    "全角英数を半角化" {
        val tokens = ProductMatcher.normalizeTitle("ＡＢＣ１２３ 商品")
        tokens.any { it.contains("abc123") } shouldBe true
    }

    // ── グルーピング ──────────────────────────────────────────────────────
    "同一商品を1グループにまとめる" {
        val products = listOf(
            product("A1", "ソニー WH-1000XM5 ブラック", Platform.AMAZON, 40000),
            product("R1", "SONY WH-1000XM5 黒", Platform.RAKUTEN, 38000),
            product("Y1", "全く別の商品 掃除機 XYZ", Platform.YAHOO, 5000),
        )
        val groups = ProductMatcher.groupByIdentity(products)
        groups.size shouldBe 2
    }

    "グループ内は最安値順" {
        val products = listOf(
            product("A1", "WH-1000XM5", Platform.AMAZON, 40000),
            product("R1", "WH-1000XM5", Platform.RAKUTEN, 38000),
        )
        val groups = ProductMatcher.groupByIdentity(products)
        groups.first().first().totalPrice shouldBe 38000
    }

    "JAN コードが同じ商品は確実に1グループ (タイトル相違でも)" {
        val products = listOf(
            product("A1", "ソニー ヘッドホン 黒", Platform.AMAZON, 40000, jan = "4548736112001"),
            product("R1", "全然違うタイトル 楽天限定", Platform.RAKUTEN, 38000, jan = "4548736112001"),
            product("Y1", "別商品", Platform.YAHOO, 5000, jan = "9999999999999"),
        )
        val groups = ProductMatcher.groupByIdentity(products)
        groups.size shouldBe 2  // JAN で2グループ
        // JAN 一致グループは最安値順
        val janGroup = groups.first { it.size == 2 }
        janGroup.first().totalPrice shouldBe 38000
    }

    "JAN あり/なし混在でも正しくグループ化" {
        val products = listOf(
            product("A1", "WH-1000XM5", Platform.AMAZON, 40000, jan = "4548736112001"),
            product("R1", "WH-1000XM5 中古", Platform.RAKUTEN, 20000),  // JANなし
            product("Y1", "コーヒー豆", Platform.YAHOO, 1500),  // JANなし、別物
        )
        val groups = ProductMatcher.groupByIdentity(products)
        // 少なくともコーヒー豆は独立グループ
        groups.any { g -> g.any { it.title.contains("コーヒー") } && g.size == 1 } shouldBe true
    }

    // ── 異なる商品 ────────────────────────────────────────────────────────
    "全く異なる商品は低類似度" {
        val a = product("A1", "コーヒー豆 ブラジル 500g")
        val b = product("R1", "ゲーミングマウス ロジクール")
        ProductMatcher.isMatch(a, b) shouldBe false
    }

    "空タイトルでも例外なし" {
        val a = product("A1", "")
        val b = product("R1", "")
        ProductMatcher.similarity(a, b) // 例外が出なければOK
    }
})
