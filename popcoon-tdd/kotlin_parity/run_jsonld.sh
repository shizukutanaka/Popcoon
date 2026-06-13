#!/usr/bin/env bash
# run_jsonld.sh — execute-verify the pure stockFromAvailability (FallbackScraper's
# JSON-LD schema.org availability -> stockCount) WITHOUT an Android SDK, using the
# Kotlin compiler bundled in the Gradle distribution.
#
# FallbackScraper depends on ktor; the availability->stock logic was kept in a
# ktor-free file (JsonLdStock.kt) so it can be compiled and asserted here.
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
  -cp "$ST" -d "$OUT/jld.jar" -nowarn -no-reflect \
  "$SRC/data/network/JsonLdStock.kt" \
  "$HERE/jsonld/JsonLdStockCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -cp "$OUT/jld.jar:$ST" \
  com.example.popcoon.data.network.JsonLdStockCheckKt
