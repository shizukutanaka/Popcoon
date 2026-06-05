package com.example.popcoon.data.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RobotsTxtTest : StringSpec({

    val ua = "Popcoon-Fallback/0.1 (+https://github.com/shizukutanaka/popcoon)"

    "空の robots は全許可" {
        RobotsTxt.isAllowed("", "/dp/B000", ua) shouldBe true
    }

    "User-agent * の Disallow を遵守" {
        val robots = """
            User-agent: *
            Disallow: /private
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/private/page", ua) shouldBe false
        RobotsTxt.isAllowed(robots, "/public/page", ua) shouldBe true
    }

    "空の Disallow は全許可" {
        val robots = """
            User-agent: *
            Disallow:
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/anything", ua) shouldBe true
    }

    "最長一致: Allow が Disallow を上書き" {
        val robots = """
            User-agent: *
            Disallow: /products
            Allow: /products/public
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/products/secret", ua) shouldBe false
        RobotsTxt.isAllowed(robots, "/products/public/item", ua) shouldBe true
    }

    "同長一致は Allow を優先" {
        val robots = """
            User-agent: *
            Disallow: /x
            Allow: /x
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/x/item", ua) shouldBe true
    }

    "UA 固有グループが * より優先される" {
        val robots = """
            User-agent: *
            Disallow:

            User-agent: Popcoon-Fallback
            Disallow: /
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/dp/B000", ua) shouldBe false
    }

    "ワイルドカード * のマッチ" {
        val robots = """
            User-agent: *
            Disallow: /*/private
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/shop/private", ua) shouldBe false
        RobotsTxt.isAllowed(robots, "/shop/public", ua) shouldBe true
    }

    "$ 終端アンカー" {
        val robots = """
            User-agent: *
            Disallow: /*.pdf$
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/files/a.pdf", ua) shouldBe false
        RobotsTxt.isAllowed(robots, "/files/a.pdf?x=1", ua) shouldBe true
    }

    "コメント行は無視" {
        val robots = """
            # comment
            User-agent: *
            Disallow: /secret  # trailing comment
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/secret/x", ua) shouldBe false
    }

    "末尾 *\$ は配下すべてに一致 (false negative 回帰)" {
        val robots = """
            User-agent: *
            Disallow: /admin*${'$'}
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/admin/users", ua) shouldBe false
        RobotsTxt.isAllowed(robots, "/admin", ua) shouldBe false
        RobotsTxt.isAllowed(robots, "/public", ua) shouldBe true
    }

    "完全一致 \$ は末尾固定" {
        val robots = """
            User-agent: *
            Disallow: /x${'$'}
        """.trimIndent()
        RobotsTxt.isAllowed(robots, "/x", ua) shouldBe false
        RobotsTxt.isAllowed(robots, "/x/y", ua) shouldBe true
    }

    "複数該当 UA グループは最長 (最も具体的) を採用" {
        val robots = """
            User-agent: popcoon
            Disallow: /

            User-agent: popcoon-fallback
            Allow: /
        """.trimIndent()
        // UA は popcoon / popcoon-fallback 両方を含むが、より具体的な後者が勝つ
        RobotsTxt.isAllowed(robots, "/dp/B000", ua) shouldBe true
    }
})
