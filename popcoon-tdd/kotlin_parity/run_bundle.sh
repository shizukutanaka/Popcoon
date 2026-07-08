#!/usr/bin/env bash
# run_bundle.sh — execute-verify the pure BundlePackDetector (set-sale unit price + verdict)
# WITHOUT an Android SDK, using the Kotlin compiler bundled in the Gradle distribution.
# BundlePackDetector is a self-contained pure object (no Product/Platform dependency).
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
  -cp "$ST" -d "$OUT/bundle.jar" -nowarn -no-reflect \
  "$SRC/feature/bundle/BundlePackDetector.kt" \
  "$HERE/bundle/BundlePackDetectorCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -cp "$OUT/bundle.jar:$ST" \
  io.github.shizukutanaka.popcoon.feature.bundle.BundlePackDetectorCheckKt
