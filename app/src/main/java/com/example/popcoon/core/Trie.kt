package com.example.popcoon.core

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 商品名オートコンプリート用 Trie。
 *
 * パフォーマンス:
 *  - Python 実装で 14倍高速化した最適化 (deque.popleft → pollFirst) を採用
 *  - ArrayDeque.pollFirst() は amortized O(1)
 *  - BFS で深さ制限なし探索 → suggest 全体が O(prefix_depth + results)
 *
 * スレッド安全性:
 *  - `ReentrantReadWriteLock` を使用
 *  - `insert` は write lock — 複数スレッドから同時呼出し可能
 *  - `suggest` は read lock — 複数スレッドが同時に読み取り可能
 *  - SearchViewModel の `init` + `launch` からの concurrent access に対応
 *
 * 重複排除:
 *  - 同一単語の二重登録を防止 (Set で管理)
 */
class Trie {
    private val root = Node()
    private val lock = ReentrantReadWriteLock()

    private class Node {
        // LinkedHashMap = 挿入順を保持。Python の dict (挿入順) と BFS の子訪問順を一致させ、
        // suggest() の候補順・limit 打ち切り時の候補集合をリファレンスと揃える
        // (HashMap だとハッシュ順になり、同じ語彙でもサジェスト結果が乖離していた)。
        val children = LinkedHashMap<Char, Node>(4)
        // Set で重複排除 (List より遅いが insert コスト O(1))
        val words = LinkedHashSet<String>(2)
    }

    /** 単語を登録する。スレッド安全。*/
    fun insert(word: String) {
        if (word.isBlank()) return
        lock.write {
            var node = root
            for (c in word) {
                node = node.children.getOrPut(c) { Node() }
            }
            node.words.add(word)
        }
    }

    /**
     * prefix に前方一致する単語を最大 limit 件返す。スレッド安全。
     * BFS で探索するため prefix が長くなるほど高速。
     */
    fun suggest(prefix: String, limit: Int = 6): List<String> {
        if (limit <= 0 || prefix.isBlank()) return emptyList()
        return lock.read {
            // 1. prefix ノードに移動
            var node = root
            for (c in prefix) {
                node = node.children[c] ?: return@read emptyList()
            }

            // 2. BFS でサジェスト候補を収集
            val results = mutableListOf<String>()
            val queue = ArrayDeque<Node>()
            queue.addLast(node)

            while (queue.isNotEmpty() && results.size < limit) {
                val cur = queue.pollFirst()
                for (word in cur.words) {
                    if (results.size >= limit) break
                    results.add(word)
                }
                for (child in cur.children.values) {
                    queue.addLast(child)
                }
            }
            results
        }
    }

    /** 登録済み単語数を返す。スレッド安全。*/
    fun size(): Int = lock.read {
        var count = 0
        val queue = ArrayDeque<Node>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.pollFirst()
            count += node.words.size
            node.children.values.forEach { queue.addLast(it) }
        }
        count
    }

    /** 全クリア。スレッド安全。*/
    fun clear() = lock.write {
        root.children.clear()
    }
}
