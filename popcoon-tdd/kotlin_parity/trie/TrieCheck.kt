import io.github.shizukutanaka.popcoon.core.Trie

/**
 * Emits Trie.suggest() outputs for a fixed word list / query set.
 * Paired with trie_oracle.py (same words, same queries); run_trie.sh diffs them.
 * Exposes child-traversal-order parity (insertion-order dict vs HashMap).
 */
fun main() {
    // Insertion order deliberately NOT sorted, so child order matters.
    val words = listOf(
        "art", "arc", "ark",
        "ant", "and", "any",
        "ace", "act",
        "apple", "april", "apex",
        "banana", "band", "bandana",
    )
    val trie = Trie()
    for (w in words) trie.insert(w)

    val queries = listOf("a" to 3, "a" to 6, "a" to 99, "ar" to 2, "an" to 5, "ap" to 2, "ban" to 4, "b" to 99)
    for ((prefix, limit) in queries) {
        println("$prefix|$limit|" + trie.suggest(prefix, limit).joinToString(","))
    }
    println("size|" + trie.size())
}
