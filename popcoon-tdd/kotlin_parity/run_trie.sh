#!/usr/bin/env bash
# run_trie.sh — differential parity for the autocomplete Trie: Kotlin core/Trie.kt
# vs popcoon_core.Trie. Compares suggest() ordering/truncation across queries.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/io/github/shizukutanaka/popcoon"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT

KC="$(find "$HOME/.gradle" -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | head -1 || true)"
if [[ -z "$KC" ]]; then
  echo "ERROR: kotlin-compiler-embeddable not found; run './gradlew --version' once." >&2; exit 2
fi
LIB="$(dirname "$KC")"
ST="$(find "$LIB" -name 'kotlin-stdlib-2*.jar' | grep -v sources | head -1)"

java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$ST" -d "$OUT/trie.jar" -nowarn -no-reflect \
  "$SRC/core/Trie.kt" \
  "$HERE/trie/TrieCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -cp "$OUT/trie.jar:$ST" TrieCheckKt > "$OUT/kotlin.txt"
python3 "$HERE/trie/trie_oracle.py" > "$OUT/python.txt"

if diff -u "$OUT/python.txt" "$OUT/kotlin.txt" > "$OUT/diff.txt"; then
  echo "TRIE PARITY: all queries match"
else
  echo "TRIE PARITY: MISMATCH (- python / + kotlin)"
  cat "$OUT/diff.txt"
  exit 1
fi
