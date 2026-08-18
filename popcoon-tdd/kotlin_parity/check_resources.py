#!/usr/bin/env python3
"""check_resources.py — Kotlin から参照される `R.*` が実在するかを静的に検査する。

背景 (2026-08):
  app モジュールの 131 ファイルのうち、本環境で実コンパイルできるのは Android 非依存の
  34 ファイルだけ (`run_compile_core.sh`)。そのハーネスは `values/strings.xml` から
  R スタブを生成するので `R.string.*` の未定義は捕まえられるが、**対象外の 85 ファイル**
  (Compose/Room/Hilt 依存) の参照は一切見ていない。`R.string.*` 以外の
  `R.drawable` / `R.color` / `R.plurals` / `R.xml` / `R.style` / `R.mipmap` / `R.id` も
  どこからも検査されていなかった。

  `R.string.foo` のタイプミスや、リソースを消したのに参照が残っている状態は
  `assembleDebug` で確実にコンパイルエラーになる — つまり CI を有効化した瞬間に
  赤くなる類の欠陥で、しかも純粋に静的に決まる。CI が無い間もここで塞ぐ。

`check_overrides.py` と同じ位置づけ: Kotlin コンパイラの代替ではなく、
コンパイル不能領域まで含めて **特定の回帰クラスだけ** を塞ぐ。

偽陽性を避けるための方針:
  - リソースの種類ごとに定義元を明示的にモデル化する
  - モデル化していない種類が使われていたら、**エラーにせず警告して数える**
    (知らない領域を「未定義」と断定しない)
  - `R.id` は values だけでなく layout の `@+id/` 宣言からも集める
"""
import collections
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
SRC_DIRS = [ROOT / "app/src/main/java"]

# values/*.xml で `<TAG name="...">` として定義される種類 → R の種類名
VALUE_TAGS = {
    "string": "string",
    "plurals": "plurals",
    "color": "color",
    "dimen": "dimen",
    "bool": "bool",
    "integer": "integer",
    "integer-array": "array",
    "string-array": "array",
    "array": "array",
    "style": "style",
    "attr": "attr",
    "declare-styleable": "styleable",
    "item": None,  # `<item name="x" type="id"/>` は下で type を見て振り分ける
}

# ディレクトリ名がそのまま R の種類になるもの (ファイル名 = リソース名)
FILE_DIRS = {"drawable", "mipmap", "layout", "xml", "anim", "animator", "menu", "raw", "font"}

# 別名 (R.drawable.x が mipmap/ にあっても解決する等)
ALIAS = {
    "drawable": ("drawable", "mipmap"),
    "mipmap": ("mipmap", "drawable"),
    "array": ("array",),
}


def collect_defined() -> dict[str, set[str]]:
    defined: dict[str, set[str]] = collections.defaultdict(set)
    for f in RES.rglob("*.xml"):
        head = f.relative_to(RES).parts[0]
        base = head.split("-")[0]
        text = io.open(f, encoding="utf-8").read()
        if base == "values":
            for tag, kind in VALUE_TAGS.items():
                if kind is None:
                    for m in re.finditer(r'<item\s+[^>]*name="([^"]+)"[^>]*type="([^"]+)"', text):
                        defined[m.group(2)].add(m.group(1))
                    continue
                defined[kind] |= set(re.findall(r"<%s\s+[^>]*name=\"([^\"]+)\"" % re.escape(tag), text))
        else:
            if base in FILE_DIRS:
                defined[base].add(f.stem)
            # layout 等の中で宣言される id (`android:id="@+id/foo"`) も定義とみなす
            defined["id"] |= set(re.findall(r'@\+id/([A-Za-z_][A-Za-z0-9_]*)', text))
    # 画像等 XML 以外のリソースファイル
    for f in RES.rglob("*"):
        if f.is_dir() or f.suffix == ".xml":
            continue
        base = f.relative_to(RES).parts[0].split("-")[0]
        if base in FILE_DIRS:
            defined[base].add(f.name.split(".")[0])
    return defined


def collect_used() -> dict[str, set[tuple[str, str]]]:
    used: dict[str, set[tuple[str, str]]] = collections.defaultdict(set)
    for d in SRC_DIRS:
        for f in sorted(d.rglob("*.kt")):
            text = io.open(f, encoding="utf-8").read()
            rel = str(f.relative_to(ROOT))
            for kind, name in re.findall(r"\bR\.(\w+)\.(\w+)", text):
                used[kind].add((name, rel))
    return used


def main() -> int:
    defined = collect_defined()
    used = collect_used()

    missing, unmodeled = [], []
    total = 0
    for kind, entries in sorted(used.items()):
        pool: set[str] = set()
        for k in ALIAS.get(kind, (kind,)):
            pool |= defined.get(k, set())
        if not pool:
            # この種類の定義元を 1 つも把握できていない → 未定義と断定しない
            unmodeled.append(f"R.{kind}.* ({len(entries)} 参照)")
            continue
        for name, f in sorted(entries):
            total += 1
            if name not in pool:
                missing.append(f"{f}: R.{kind}.{name} がリソースに存在しない")

    print(f"resource check: {total} R.* references / "
          f"{sum(len(v) for v in defined.values())} resources defined")
    for u in unmodeled:
        print(f"  [skip] 定義元を把握していない種類: {u}")
    if missing:
        print("RESOURCE CHECK: FAILED", file=sys.stderr)
        for m in missing:
            print("  " + m, file=sys.stderr)
        return 1
    print("RESOURCE CHECK: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
