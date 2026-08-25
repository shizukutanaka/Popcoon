#!/usr/bin/env bash
# run_widget.sh — execute-verify WidgetVerdict.topForWidget (order-before-truncate)
# WITHOUT an Android SDK. WidgetVerdict は純関数なので単体でコンパイルできる。
#
# Python オラクルは持たない (ウィジェット固有の表示規則で二重管理する意味が無い) が、
# 「上限を掛ける前に優先順位を付ける」不変条件は純ロジックなので実行検証できる。
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

java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$ST" -d "$OUT/wid.jar" -nowarn -no-reflect \
  "$SRC/feature/watchlist/WidgetVerdict.kt" \
  "$HERE/widget/WidgetTopCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 \
  -cp "$OUT/wid.jar:$ST" \
  io.github.shizukutanaka.popcoon.feature.watchlist.WidgetTopCheckKt
