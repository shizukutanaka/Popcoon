#!/usr/bin/env bash
# run_points.sh — execute-verify the pure PointSimulator (実質価格 = sticker+shipping-points)
# WITHOUT an Android SDK, using the Kotlin compiler bundled in the Gradle distribution.
# PointSimulator + Product are pure (java.time only); they compile standalone.
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
SER="$(find "$LIB" -name 'kotlinx-serialization-core-jvm-*.jar' | head -1):$(find "$LIB" -name 'kotlinx-serialization-json-jvm-*.jar' | head -1)"

java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$ST:$SER" -d "$OUT/points.jar" -nowarn -no-reflect \
  "$SRC/data/model/Product.kt" \
  "$SRC/feature/points/PointSimulator.kt" \
  "$HERE/points/PointSimulatorCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -cp "$OUT/points.jar:$ST:$SER" \
  com.example.popcoon.feature.points.PointSimulatorCheckKt
