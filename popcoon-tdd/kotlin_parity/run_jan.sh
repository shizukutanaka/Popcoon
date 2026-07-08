#!/usr/bin/env bash
# run_jan.sh — execute-verify JanCodeQuery (JAN/EAN check-digit validation)
# WITHOUT an Android SDK, using the Gradle-bundled Kotlin compiler.
# JanCodeQuery is a self-contained pure object.
set -euo pipefail

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
  -cp "$ST" -d "$OUT/jan.jar" -nowarn -no-reflect \
  "$SRC/feature/barcode/JanCodeQuery.kt" \
  "$HERE/jan/JanCodeQueryCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -cp "$OUT/jan.jar:$ST" \
  io.github.shizukutanaka.popcoon.feature.barcode.JanCodeQueryCheckKt
