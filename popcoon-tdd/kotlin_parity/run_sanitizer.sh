#!/usr/bin/env bash
# run_sanitizer.sh — execute-verify LogSanitizer against the shared PII corpus
# WITHOUT an Android SDK. LogSanitizer は Android に依存しない純関数なので単体で
# コンパイルできる (run_compile_core.sh の対象にも入る)。
#
# 同じ corpus.tsv を backend の test/sanitizer-corpus.test.ts が TypeScript 実装
# (sanitizePii) に対して回すため、2 言語が同一規則であることが fixture drift 無しに
# 検証される。期待値は正規表現から手導出したもので、どちらの実装の出力でもない。
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
  -cp "$ST" -d "$OUT/san.jar" -nowarn -no-reflect \
  "$SRC/core/LogSanitizer.kt" \
  "$HERE/sanitizer/LogSanitizerCheck.kt" 2>&1 | grep -v 'unable to find kotlin' || true

java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 \
  -cp "$OUT/san.jar:$ST" \
  io.github.shizukutanaka.popcoon.core.LogSanitizerCheckKt "$HERE/sanitizer/corpus.tsv"
