#!/usr/bin/env bash
# run_calendar.sh — execute-verify SaleCalendar's ordering / coverage invariants
# WITHOUT an Android SDK. SaleCalendar depends only on java.time + Platform, so it
# compiles standalone (run.sh already compiles it for BuyTimingScorer).
#
# Python オラクルは持たない (日付固定の外部仕様データを二重管理する意味が無い) ため、
# 純ロジック側の不変条件 — activeSales の重要度順・platform フィルタ・
# nextMajorSale の最小性・upcomingSales の昇順/horizon — を実行して表明する。
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
  -cp "$ST:$SER" -d "$OUT/cal.jar" -nowarn -no-reflect \
  "$SRC/data/model/Product.kt" \
  "$SRC/feature/calendar/SaleCalendar.kt" \
  "$HERE/calendar/SaleCalendarCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT/cal.jar:$ST:$SER" \
  io.github.shizukutanaka.popcoon.feature.calendar.SaleCalendarCheckKt
