#!/usr/bin/env bash
# run_alerts.sh — execute-verify the pure PriceAlertEvaluator WITHOUT an Android SDK.
# PriceAlertEvaluator has no Android dependency, so it compiles standalone.
# Permanently guards the edge-trigger semantics (Tier 53/54) in the CI parity job,
# which actually runs (unlike the Kotest suite, which needs the Android SDK).
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
  -cp "$ST" -d "$OUT/alerts.jar" -nowarn -no-reflect \
  "$SRC/feature/notification/PriceAlertEvaluator.kt" \
  "$HERE/alerts/PriceAlertEvaluatorCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -cp "$OUT/alerts.jar:$ST" \
  io.github.shizukutanaka.popcoon.feature.notification.PriceAlertEvaluatorCheckKt
