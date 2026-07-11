#!/usr/bin/env bash
# run_matcher.sh — execute-verify ProductMatcher model-number extraction / title
# normalization WITHOUT an Android SDK, using the Gradle-bundled Kotlin compiler.
# ProductMatcher depends only on the Product model.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/io/github/shizukutanaka/popcoon"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT

KC="$(find "$HOME/.gradle" ${GRADLE_HOME:+"$GRADLE_HOME/lib"} /opt/gradle-*/lib /usr/share/gradle*/lib -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | head -1 || true)"
if [[ -z "$KC" ]]; then
  echo "ERROR: kotlin-compiler-embeddable not found; run './gradlew --version' once." >&2; exit 2
fi
LIB="$(dirname "$KC")"
ST="$(find "$LIB" -name 'kotlin-stdlib-2*.jar' | grep -v sources | head -1)"
SER="$(find "$LIB" -name 'kotlinx-serialization-core-jvm-*.jar' | head -1):$(find "$LIB" -name 'kotlinx-serialization-json-jvm-*.jar' | head -1)"

java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$ST:$SER" -d "$OUT/matcher.jar" -nowarn -no-reflect \
  "$SRC/data/model/Product.kt" \
  "$SRC/feature/matching/ProductMatcher.kt" \
  "$HERE/matching/ProductMatcherCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -cp "$OUT/matcher.jar:$ST:$SER" \
  io.github.shizukutanaka.popcoon.feature.matching.ProductMatcherCheckKt
