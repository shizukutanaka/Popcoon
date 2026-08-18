#!/usr/bin/env python3
"""check_test_refs.py — テストが参照する本番シンボルが実在するかを静的に検査する。

背景 (2026-08):
  app/src/test の 64 ファイル (kotest) は本環境で **一切コンパイルできない** —
  kotest の jar が無く、CI も未稼働。つまり本番 API の名前や引数を変えたときに
  テスト側の追随漏れがあっても、CI を有効化するまで誰も気付けない。
  本セッションだけでも `PriceSyncPlanner.selectNotifications` → `plan`、
  `Drop(item = ...)` → 個別フィールド、といった改名を行っており、
  1 箇所直し忘れれば CI 初回実行が赤くなる。

  「テストがコンパイルできない」ことは変えられないが、
  **`Object.member` の member が本番に存在するか** だけは静的に決まる。
  `check_overrides.py` / `check_resources.py` / `check_when_exhaustive.py` と同じ位置づけで、
  この 1 つの回帰クラスだけを塞ぐ。

健全性 (偽陽性を出さないための方針):
  - レシーバが **プロジェクト内の object / enum class / companion を持つ class** と
    確実に分かる場合だけ判定する。data class や外部ライブラリの型は対象外
  - 同名の宣言が複数ある場合はメンバー集合の **和** を取る (緩い方向 = 誤報しない方向)
  - コンパイラが生成するメンバー (values/entries/valueOf/name/ordinal/copy 等) は許可
  - 拡張関数はプロジェクト全体のトップレベル宣言から集めて許可
"""
import collections
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MAIN_DIR = ROOT / "app/src/main/java"
TEST_DIRS = [ROOT / "app/src/test/java", ROOT / "app/src/androidTest/java"]

# Kotlin/コンパイラが暗黙に用意するメンバー
BUILTIN = {
    "values", "entries", "valueOf", "name", "ordinal", "copy", "equals", "hashCode",
    "toString", "javaClass", "INSTANCE", "Companion",
    "let", "also", "apply", "run", "takeIf", "takeUnless", "to",
}


def strip_noncode(s: str) -> str:
    s = re.sub(r"/\*.*?\*/", " ", s, flags=re.S)
    s = re.sub(r"//[^\n]*", " ", s)
    s = re.sub(r'"""(?:.|\n)*?"""', ' "" ', s)
    s = re.sub(r'"(?:\\.|[^"\\\n])*"', ' "" ', s)
    return s


def _matching_brace(text: str, open_idx: int) -> int:
    depth, i = 0, open_idx
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(text) - 1


def _body_members(body: str) -> set[str]:
    out = set(re.findall(r"\b(?:fun|val|var|class|object|interface)\s+([A-Za-z_]\w*)", body))
    out |= set(re.findall(r"\benum class\s+([A-Za-z_]\w*)", body))
    return out


def _nested_types(body: str) -> set[str]:
    """入れ子で宣言された型の名前。

    `class Outer { data class Inner(...) }` の `Inner` は companion に無くても
    `Outer.Inner` で参照できる (最初の実装はこれを落として StartupTracker.StartupMetrics を
    誤って「存在しない」と報告した)。インスタンスメンバーの fun/val は
    `Outer.x` では呼べないので含めない。
    """
    return set(re.findall(r"\b(?:class|object|interface)\s+([A-Za-z_]\w*)", body))


def collect_members() -> tuple[dict[str, set[str]], set[str]]:
    """静的に `Name.member` で呼べる宣言のメンバー表と、拡張関数名の集合。"""
    members: dict[str, set[str]] = collections.defaultdict(set)
    extensions: set[str] = set()
    for f in sorted(MAIN_DIR.rglob("*.kt")):
        text = io.open(f, encoding="utf-8").read()
        # 拡張関数 (`fun Foo.bar()`) はレシーバ側の宣言に無くても呼べる
        extensions |= set(re.findall(r"\bfun\s+(?:<[^>]*>\s*)?[A-Za-z_][\w.]*\.([A-Za-z_]\w*)\s*\(", text))
        for m in re.finditer(r"\b(object|enum class|class)\s+([A-Za-z_]\w*)", text):
            kind, name = m.group(1), m.group(2)
            brace = text.find("{", m.end())
            if brace < 0:
                continue
            body = text[brace + 1:_matching_brace(text, brace)]
            if kind == "class":
                # class のインスタンスメンバーは `Name.x` で呼べない。
                # `Name.x` で到達できるのは (1) 入れ子で宣言された型、
                # (2) companion object の中身 の 2 つ。
                members[name] |= _nested_types(body)
                cm = re.search(r"\bcompanion\s+object\b[^{]*\{", body)
                if cm:
                    cbrace = brace + 1 + cm.end() - 1
                    members[name] |= _body_members(text[cbrace + 1:_matching_brace(text, cbrace)])
                continue
            members[name] |= _body_members(body)
            if kind == "enum class":
                head = body.split(";")[0]
                members[name] |= set(re.findall(r"\b([A-Z][A-Z0-9_]+)\b", head))
    return dict(members), extensions


def main() -> int:
    members, extensions = collect_members()
    allowed_anywhere = BUILTIN | extensions

    refs = 0
    missing: dict[tuple[str, str], list[str]] = collections.defaultdict(list)
    for d in TEST_DIRS:
        if not d.is_dir():
            continue
        for f in sorted(d.rglob("*.kt")):
            text = strip_noncode(io.open(f, encoding="utf-8").read())
            rel = str(f.relative_to(ROOT))
            for m in re.finditer(r"\b([A-Z]\w*)\.([a-zA-Z_]\w*)\b", text):
                recv, mem = m.group(1), m.group(2)
                if recv not in members:
                    continue  # 判定できる宣言ではない → 触らない
                refs += 1
                if mem in allowed_anywhere or mem in members[recv]:
                    continue
                line = text[:m.start()].count("\n") + 1
                missing[(recv, mem)].append(f"{rel}:{line}")

    print(f"test-ref check: {len(members)} callable declarations / {refs} references from tests")
    if missing:
        print("TEST REF CHECK: FAILED", file=sys.stderr)
        for (recv, mem), locs in sorted(missing.items()):
            print(f"  {recv}.{mem} が本番に存在しない ({len(locs)} 箇所): {locs[0]}", file=sys.stderr)
        return 1
    print("TEST REF CHECK: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
