#!/usr/bin/env bash
# run_deeplinks.sh — execute-verify the pure DeepLinks single-source-of-truth
# WITHOUT an Android SDK. DeepLinks has no Android dependency, so it compiles
# standalone. Verifies producer (product) / consumer (productKeyOrNull) round-trip.
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
  -cp "$ST" -d "$OUT/dl.jar" -nowarn -no-reflect \
  "$SRC/core/DeepLinks.kt" \
  "$HERE/deeplinks/DeepLinksCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -cp "$OUT/dl.jar:$ST" \
  io.github.shizukutanaka.popcoon.core.DeepLinksCheckKt
