"""Oracle for Trie parity: same words/queries as TrieCheck.kt, via popcoon_core.Trie."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from popcoon_core import Trie

words = [
    "art", "arc", "ark",
    "ant", "and", "any",
    "ace", "act",
    "apple", "april", "apex",
    "banana", "band", "bandana",
]
trie = Trie()
for w in words:
    trie.insert(w)

queries = [("a", 3), ("a", 6), ("a", 99), ("ar", 2), ("an", 5), ("ap", 2), ("ban", 4), ("b", 99)]
for prefix, limit in queries:
    print(f"{prefix}|{limit}|" + ",".join(trie.suggest(prefix, limit)))
print("size|" + str(trie.size()))
