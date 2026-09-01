#!/usr/bin/env bash
# run_kotest.sh — compile and EXECUTE the app's kotest specs without kotest.
#
# なぜ: この環境には Maven Central への egress が無く (repo1.maven.org /
# repo.maven.apache.org は egress プロキシが 403)、kotest の jar を取得できない。
# CI 有効化は人手ゲートなので、`app/src/test` の 63 spec は **一度も実行されていない**。
# 参照シンボルの実在は check_test_refs.py が見ているが、アサーションが真かは誰も
# 確かめていなかった。
#
# 対処: kotest 本体を待たず、テストが実際に使う 42 シンボルだけを kotest_shim/ に実装し、
# **テストファイルを 1 行も変えずに**コンパイル・実行する。
#
# 明示的な限界 (過大評価しないこと):
#  - Android/AndroidX/Hilt/ktor/Room/Compose/コルーチンに依存する spec は対象外。
#    除外した件数と理由をこのスクリプトが毎回表示する。
#  - プロパティテストは kotest 既定 1000 回ではなく POPCOON_PROPERTY_ITERATIONS
#    (既定 300) をシード固定で回す。shrinking なし。
#  - 非対応の kotest 機能を使った spec は **コンパイルエラー**で落ちる (黙って通らない)。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TESTS="$ROOT/app/src/test/java"
OUT="${POPCOON_KOTEST_OUT:-$(mktemp -d)}"
mkdir -p "$OUT"
[[ -n "${POPCOON_KOTEST_OUT:-}" ]] || trap 'rm -rf "$OUT"' EXIT

KC="$(find "$HOME/.gradle" ${GRADLE_HOME:+"$GRADLE_HOME/lib"} /opt/gradle-*/lib /usr/share/gradle*/lib -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | head -1 || true)"
if [[ -z "$KC" ]]; then
  echo "ERROR: kotlin-compiler-embeddable not found; run './gradlew --version' once." >&2; exit 2
fi
LIB="$(dirname "$KC")"
ST="$(find "$LIB" -name 'kotlin-stdlib-2*.jar' | grep -v sources | head -1)"
SER="$(find "$LIB" -name 'kotlinx-serialization-core-jvm-*.jar' | head -1):$(find "$LIB" -name 'kotlinx-serialization-json-jvm-*.jar' | head -1)"

# 1. production の core.jar を run_compile_core.sh に作らせる (対象選定を二重管理しない)
POPCOON_CORE_JAR_OUT="$OUT/core.jar" bash "$HERE/run_compile_core.sh" > "$OUT/core.log" 2>&1 || {
  echo "ERROR: run_compile_core.sh が失敗した (先にそちらを直すこと):" >&2
  tail -20 "$OUT/core.log" >&2
  exit 1
}
[[ -f "$OUT/core.jar" ]] || { echo "ERROR: core.jar が出力されなかった" >&2; exit 1; }

# 2. kotest シムをコンパイル
java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$ST" -d "$OUT/shim.jar" -nowarn \
  "$HERE"/kotest_shim/*.kt 2>&1 | grep -v 'unable to find kotlin' || true
[[ -f "$OUT/shim.jar" ]] || { echo "ERROR: kotest シムのコンパイルに失敗" >&2; exit 1; }

# 3. 実行対象の spec を選ぶ。production 側と同じ「手元に jar が無い依存」を除外する。
python3 - "$TESTS" "$OUT" <<'PY'
import pathlib, re, sys
tests_root, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
# 手元に jar が無い依存。production の run_compile_core.sh と同じ考え方。
dep = re.compile(
    r"^import (android[.x]|androidx\.|dagger\.|com\.google\.|io\.ktor|kotlinx\.coroutines|"
    r"io\.mockk|org\.robolectric|org\.junit)", re.M)
keep, skipped = [], []
for f in sorted(tests_root.rglob("*Test.kt")):
    text = f.read_text(encoding="utf-8")
    hit = dep.search(text)
    if hit:
        skipped.append(f"{f.relative_to(tests_root)}\t{hit.group(1)}")
    else:
        keep.append(str(f))
(out / "specs.txt").write_text("\n".join(keep), encoding="utf-8")
(out / "skipped.txt").write_text("\n".join(skipped), encoding="utf-8")
print(f"spec 候補: {len(keep)} 実行可能 / {len(skipped)} 除外 (手元に jar が無い依存)")
PY

mapfile -t SPECS < "$OUT/specs.txt"

# 4. コンパイル → 失敗したファイルを外して再試行 (production 側の型が無い spec が残るため)。
#    「なぜ外れたか」は必ず表示する。黙って縮めない。
attempt=0
while :; do
  attempt=$((attempt + 1))
  python3 - "$OUT" "${SPECS[@]}" <<'PY'
import pathlib, re, sys
out = pathlib.Path(sys.argv[1]); files = sys.argv[2:]
entries = []
for p in files:
    text = pathlib.Path(p).read_text(encoding="utf-8")
    pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
    for m in re.finditer(r"^class\s+(\w+)\s*:\s*StringSpec", text, re.M):
        entries.append(f"{pkg.group(1)}.{m.group(1)}")
body = ",\n    ".join(f'"{e}" to {{ {e}() }}' for e in entries)
out.joinpath("GeneratedSpecList.kt").write_text(
    "import io.kotest.core.spec.style.StringSpec\n"
    "import io.kotest.runner.runSpecs\n\n"
    "fun main() {\n"
    f"    val specs: List<Pair<String, () -> StringSpec>> = listOf(\n    {body}\n    )\n"
    "    kotlin.system.exitProcess(runSpecs(specs))\n"
    "}\n", encoding="utf-8")
print(len(entries), file=sys.stderr)
PY
  set +e
  java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -cp "$ST:$SER:$OUT/core.jar:$OUT/shim.jar" -d "$OUT/specs.jar" -nowarn \
    "$OUT/GeneratedSpecList.kt" "${SPECS[@]}" > "$OUT/compile.log" 2>&1
  rc=$?
  set -e
  grep -E ':[0-9]+:[0-9]+: error:' "$OUT/compile.log" > "$OUT/errors.log" || true
  if [[ ! -s "$OUT/errors.log" && -f "$OUT/specs.jar" ]]; then
    break
  fi
  if [[ $attempt -gt 8 ]]; then
    echo "ERROR: 除外を 8 回繰り返しても spec がコンパイルできない:" >&2
    head -20 "$OUT/errors.log" >&2
    exit 1
  fi
  # エラーが出たファイルを外す。理由 (最初のエラー 1 行) を必ず表示する。
  BAD="$(sed -n 's|^\(.*\.kt\):[0-9]*:[0-9]*: error:.*|\1|p' "$OUT/errors.log" | sort -u)"
  if [[ -z "$BAD" ]]; then
    echo "ERROR: コンパイルエラーの出所を特定できない:" >&2
    head -20 "$OUT/errors.log" >&2
    exit 1
  fi
  while IFS= read -r b; do
    [[ -z "$b" ]] && continue
    abs="$(realpath "$b" 2>/dev/null || echo "$b")"
    reason="$(grep -m1 -F "$b:" "$OUT/errors.log" | sed 's|^.*: error: ||' | cut -c1-100)"
    echo "  [除外] ${abs#$ROOT/}: $reason"
    for i in "${!SPECS[@]}"; do
      [[ "${SPECS[$i]}" == "$abs" ]] && unset 'SPECS[i]'
    done
  done <<< "$BAD"
  SPECS=("${SPECS[@]}")
  rm -f "$OUT/specs.jar"
done

echo "実行する spec ファイル: ${#SPECS[@]}"
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 \
  -cp "$OUT/specs.jar:$OUT/core.jar:$OUT/shim.jar:$ST:$SER" GeneratedSpecListKt
