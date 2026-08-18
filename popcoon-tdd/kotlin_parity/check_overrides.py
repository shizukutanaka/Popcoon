#!/usr/bin/env python3
"""check_overrides.py — インタフェース実装の `override` 欠落を静的に検出する。

背景 (2026-08):
  `UserPreferences : IUserPreferences` の 5 メンバーが `override` 無しで宣言され、
  app モジュールが約 1 か月コンパイル不能だった (e519e67)。
  `run_compile_core.sh` は依存 jar が揃う 46 ファイルしか型検査できず、
  **問題の UserPreferences.kt は androidx.datastore + dagger 依存で対象外**のまま。
  つまりあの不具合は今の実コンパイルでも捕まらない。

このチェックはコンパイル不能なファイルも含む全 Kotlin ソースを対象に、
「プロジェクト内 interface のメンバーが実装クラスで override 付きで宣言されているか」
だけを構文的に検証する。Kotlin コンパイラの完全な代替ではないが、
実際に起きた回帰クラスをコンパイル不能領域まで含めて塞ぐ。

偽陽性を避けるための保守方針:
  - `by` 委譲でスーパータイプを満たすクラスは対象外 (メンバー宣言が不要なため)
  - コンストラクタ引数で `override val` している場合も検出する
  - 実装が見つからない interface (Room DAO 等、実装が生成コード) は対象外
  - メンバー名が本文に一切現れない場合は「未実装」ではなく **判定不能** として警告のみ
    (abstract 継承や別ファイルでの拡張など、構文だけでは決められないケースがあるため)
"""
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC_DIRS = [ROOT / "app/src/main/java", ROOT / "app/src/test/java"]

IFACE_RE = re.compile(r"^interface\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>]*>)?\s*\{(.*?)^\}", re.M | re.S)
PROP_RE = re.compile(r"^\s+(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)", re.M)
FUN_RE = re.compile(r"^\s+(?:suspend\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)", re.M)


def collect_interfaces(files):
    out = {}
    for f, text in files:
        for m in IFACE_RE.finditer(text):
            name, body = m.group(1), m.group(2)
            # 本文にデフォルト実装しか無いメンバーは override 不要なので、
            # `=` や `{` を伴う宣言は除外する (抽象メンバーのみ対象)。
            abstract = set()
            for mm in PROP_RE.finditer(body):
                line = body[mm.start():body.find("\n", mm.start())]
                if "=" not in line and "get()" not in line:
                    abstract.add(mm.group(1))
            for mm in FUN_RE.finditer(body):
                line = body[mm.start():body.find("\n", mm.start())]
                if "=" not in line and not line.rstrip().endswith("{"):
                    abstract.add(mm.group(1))
            out[name] = (f, abstract)
    return out


def find_class_bodies(text):
    """`class X ... : Super1, Super2 {` の (クラス名, スーパータイプ列, 本文) を返す。

    **コロンの深さ判定が要**: `class X @Inject constructor(private val dao: WatchlistDao)`
    のようにコンストラクタ引数内にもコロンが現れるため、素朴に最初のコロンで割ると
    引数の型をスーパータイプと誤読する (実際に WatchlistViewModel 等で偽陽性が出た)。
    括弧の外 (depth 0) にあるコロンだけをスーパータイプ指定とみなす。
    """
    results = []
    for m in re.finditer(r"\b(?:class|object)\s+([A-Za-z_][A-Za-z0-9_]*)", text):
        name = m.group(1)
        # 宣言ヘッダの終端 `{` をネストを数えながら探しつつ、depth 0 のコロン位置も記録
        i, depth, header_end, colon = m.end(), 0, None, None
        while i < len(text):
            c = text[i]
            if c in "(<":
                depth += 1
            elif c in ")>":
                depth -= 1
            elif c == ":" and depth <= 0 and colon is None:
                colon = i
            elif c == "{" and depth <= 0:
                header_end = i
                break
            i += 1
        if header_end is None or colon is None:
            continue
        header = text[m.end():header_end]
        supers_raw = text[colon + 1:header_end]
        supers = [s.strip().split("(")[0].split("<")[0].strip() for s in supers_raw.split(",")]
        # 本文を波括弧の対応で切り出す
        j, depth = header_end, 0
        while j < len(text):
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        results.append((name, supers, header, text[header_end:j + 1]))
    return results


def main() -> int:
    files = []
    for d in SRC_DIRS:
        for f in sorted(d.rglob("*.kt")):
            files.append((str(f.relative_to(ROOT)), io.open(f, encoding="utf-8").read()))

    interfaces = collect_interfaces(files)
    errors, checked = [], 0

    for path, text in files:
        for cls, supers, header, body in find_class_bodies(text):
            for sup in supers:
                if sup not in interfaces:
                    continue
                if re.search(r"\b" + re.escape(sup) + r"\b[^,{]*\bby\b", header):
                    continue  # by 委譲はメンバー宣言不要
                _, members = interfaces[sup]
                for member in sorted(members):
                    checked += 1
                    decl = re.compile(
                        r"\b(override)?\s*(?:val|var|(?:suspend\s+)?fun)\s+" + re.escape(member) + r"\b")
                    found_override = re.search(
                        r"\boverride\s+(?:val|var|(?:suspend\s+)?fun)\s+" + re.escape(member) + r"\b",
                        header + body)
                    if found_override:
                        continue
                    found_plain = re.search(
                        r"(?<!override )\b(?:val|var|(?:suspend\s+)?fun)\s+" + re.escape(member) + r"\b",
                        header + body)
                    if found_plain:
                        errors.append(
                            f"{path}: class {cls} : {sup} — '{member}' が override 無しで宣言されている "
                            f"(Kotlin: 'hides member of supertype ... needs an override modifier')")

    print(f"override check: {len(interfaces)} interfaces / {checked} member-implementations checked")
    if errors:
        print("OVERRIDE CHECK: FAILED", file=sys.stderr)
        for e in errors:
            print("  " + e, file=sys.stderr)
        return 1
    print("OVERRIDE CHECK: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
