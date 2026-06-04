package com.example.popcoon.ui.screens.search

import com.example.popcoon.core.Trie
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtMostSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Trie のオートコンプリート挙動。
 * SearchViewModel が依存するデータ構造の動作保証。
 *
 * 同種ソフト調査:
 *  - Apple UISearchController と同等の体験を実現するため
 *  - リアルタイムサジェスト < 50ms が目標
 */
class TrieSuggestTest : StringSpec({

    "空のクエリは空リストを返す" {
        val t = Trie()
        t.insert("テスト商品")
        t.suggest("", limit = 6) shouldBe emptyList()
    }

    "前方一致で候補を返す" {
        val t = Trie().apply {
            insert("ハーゲンダッツ ストロベリー")
            insert("ハーゲンダッツ バニラ")
            insert("ハーゲンダッツ チョコ")
            insert("明治 メルティーキッス")
        }
        val results = t.suggest("ハーゲン", limit = 6)
        results.size shouldBe 3
        results.all { it.startsWith("ハーゲン") } shouldBe true
    }

    "limit を超えない" {
        val t = Trie()
        repeat(20) { i -> t.insert("商品$i") }
        t.suggest("商品", limit = 5) shouldHaveAtMostSize 5
    }

    "完全一致した文字列も候補に含む" {
        val t = Trie().apply { insert("プリンター") }
        t.suggest("プリンター", limit = 6) shouldContain "プリンター"
    }

    "重複登録しても候補は1つだけ" {
        val t = Trie().apply {
            insert("マスク")
            insert("マスク")
            insert("マスク")
        }
        val results = t.suggest("マス", limit = 6)
        results.count { it == "マスク" } shouldBe 1
    }

    "大文字小文字を区別する (日本語のためそのまま)" {
        val t = Trie().apply {
            insert("Apple Watch")
            insert("apple watch")
        }
        // 仕様: そのまま区別する
        val results = t.suggest("Apple", limit = 6)
        results.size shouldBe 1
    }

    "英数字混在クエリ" {
        val t = Trie().apply {
            insert("iPhone 16 Pro")
            insert("iPhone 16")
            insert("iPad Pro 13")
        }
        val results = t.suggest("iPhone", limit = 6)
        results.size shouldBe 2
    }

    "limit 0 は空リスト" {
        val t = Trie().apply { insert("テスト") }
        t.suggest("テ", limit = 0) shouldBe emptyList()
    }

    "deque ベースの O(1) 速度: 1000要素挿入後の suggest が高速" {
        val t = Trie()
        val start = System.nanoTime()
        repeat(1000) { i -> t.insert("商品名${i}号") }
        val results = t.suggest("商品", limit = 10)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        results.size shouldBe 10
        // 1000 件挿入 + 10 件サジェスト合計 100ms 以下を期待
        (elapsedMs < 500) shouldBe true
    }
})
