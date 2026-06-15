#!/usr/bin/env bash
# run_currency.sh — execute-verify the pure CurrencyFormatter under non-US locales
# WITHOUT an Android SDK. CurrencyFormatter depends only on java.util.Locale, so it
# compiles standalone. Proves the Locale.US guard survives de_DE / ar default locales.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/com/example/popcoon"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT

KC="$(find "$HOME/.gradle" -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | head -1 || true)"
if [[ -z "$KC" ]]; then
  echo "ERROR: kotlin-compiler-embeddable not found; run './gradlew --version' once." >&2; exit 2
fi
LIB="$(dirname "$KC")"
ST="$(find "$LIB" -name 'kotlin-stdlib-2*.jar' | grep -v sources | head -1)"

java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$ST" -d "$OUT/cur.jar" -nowarn -no-reflect \
  "$SRC/core/CurrencyFormatter.kt" \
  "$HERE/currency/CurrencyFormatterCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -cp "$OUT/cur.jar:$ST" \
  com.example.popcoon.core.CurrencyFormatterCheckKt
