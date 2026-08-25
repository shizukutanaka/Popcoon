#!/usr/bin/env python3
"""check_truncation.py — コレクションを `take` で切る前に順序付けがあるか検査する。

背景 (2026-08): 「上限を掛ける前に優先順位を付けていない」欠陥を 2 回別々に見つけた。
どちらもユーザーに見える情報が静かに落ちていた:

  PriceSyncPlanner (RESEARCH-2026-08 §6)
    通知上限を超えた値下がりを `take()` で破棄。基準価格は書き戻し済みなので
    **二度と再通知されない**。「上限は割り込みの制御」という宣言に反していた。

  SearchViewModel / ProductRow (RESEARCH-2026-08 §12)
    行あたり `warnings.take(2)` なのに検出順のまま渡しており、
    支払額が 3 割増える DRIP_PRICING(HIGH) が CHARM_PRICING(LOW) に押し出されていた。

3 回目を人間の注意力に頼らないための歯止め。

規則:
  コレクションに対する `.take(n)` / `.takeLast(n)` は、
  (a) 同じ式の中に並べ替えがある (`sorted*` / `sortedWith` / `prioritize` 等)、
  (b) 受け側の `val` 初期化式に並べ替えがある、
  (c) `// truncate-order-ok: <理由>` を直前行か同一行に書く、
  のいずれかを満たすこと。

  文字列の切り詰め (`title.take(20)` 等) は対象外。引数が
  STRING_CUTOFF 以上の整数リテラルなら文字列とみなす — 本リポジトリの実測では
  コレクション側は 2〜7、文字列側は 15〜8000 で綺麗に分かれている。
  境界に掛かる書き方をしたくなったら (c) の注記で意図を残すこと。
"""
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC_DIRS = [ROOT / "app/src/main/java"]

# これ以上の整数リテラルは文字列の切り詰めとみなす (実測: コレクション 2-7 / 文字列 15+)
STRING_CUTOFF = 12

TAKE = re.compile(r"\.take(?:Last)?\s*\(")

# 並べ替え / 優先順位付けとみなす操作
ORDERED = re.compile(
    r"\bsorted\b|\bsortedBy\b|\bsortedByDescending\b|\bsortedWith\b|\bsortBy\b"
    r"|\bsortedDescending\b|\bprioritize\b|\breversed\b|\bORDER\s+BY\b"
)

ANNOTATION = re.compile(r"truncate-order-ok\s*:")


def strip_noncode(s: str) -> str:
    """コメントを潰す。行数は保つ (複数行コメントは改行だけ残す)。"""
    def keep_newlines(m: re.Match) -> str:
        return "\n" * m.group(0).count("\n")
    s = re.sub(r"/\*.*?\*/", keep_newlines, s, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def arg_of(text: str, open_paren: int) -> str:
    """`(` の位置から対応する `)` までの中身を返す。"""
    depth, i = 0, open_paren
    while i < len(text):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1:i]
        i += 1
    return ""


def receiver_of(text: str, dot: int) -> str:
    """`.take` の直前にある受け側の式を、行頭かステートメント区切りまで遡って返す。

    `xs.sortedWith(\n  ...\n).take(3)` のように**複数行に折り返したチェーン**が
    実際に多いので、直前が閉じ括弧 `)` `}` `]` なら対応する開き括弧まで
    改行をまたいで遡る。ここを見落とすと受け側が `')'` だけになり、
    並べ替えが有るのに無いと誤報する。
    """
    CLOSERS = {")": "(", "}": "{", "]": "["}
    i = dot
    while i > 0:
        ch = text[i - 1]
        if ch in CLOSERS:
            depth, j = 0, i - 1
            while j >= 0:
                if text[j] in ")}]":
                    depth += 1
                elif text[j] in "({[":
                    depth -= 1
                    if depth == 0:
                        break
                j -= 1
            if j < 0:
                break
            i = j
            continue
        if ch in "\n;":
            break
        if ch == "{":
            break
        i -= 1
    return text[i:dot]


def main() -> int:
    offenders, checked, skipped_str = [], 0, 0
    for d in SRC_DIRS:
        for f in sorted(d.rglob("*.kt")):
            rel = str(f.relative_to(ROOT))
            raw = io.open(f, encoding="utf-8").read()
            raw_lines = raw.split("\n")
            text = strip_noncode(raw)

            # `const val NAME = 23` 形式の定数を解決する (MAX_TAG_LENGTH 等)
            consts = {n: int(v) for n, v in re.findall(
                r"\bconst\s+val\s+(\w+)\s*(?::\s*Int\s*)?=\s*(\d+)", text)}

            for m in TAKE.finditer(text):
                arg = arg_of(text, m.end() - 1).strip()
                literal = None
                if re.fullmatch(r"\d+", arg):
                    literal = int(arg)
                elif arg in consts:
                    literal = consts[arg]
                if literal is not None and literal >= STRING_CUTOFF:
                    skipped_str += 1
                    continue

                line_no = text[:m.start()].count("\n") + 1
                # (c) 注記 — 元テキスト (コメント込み) の同一行か直前 3 行
                window = raw_lines[max(0, line_no - 4):line_no]
                if any(ANNOTATION.search(w) for w in window):
                    checked += 1
                    continue

                recv = receiver_of(text, m.start())
                # (a) 同一式に並べ替えがある
                if ORDERED.search(recv):
                    checked += 1
                    continue

                # (b) 受け側の識別子の val 初期化式に並べ替えがある
                ident = re.search(r"(\w+)\s*$", recv)
                resolved = False
                if ident:
                    name = ident.group(1)
                    # 初期化式は複数行に折り返されることがあるので、宣言位置から
                    # 次の宣言/ブロック終端までを窓として見る (最大 6 行)。
                    for decl in re.finditer(
                            r"\b(?:val|var)\s+" + re.escape(name) + r"\b[^\n=]*=", text):
                        window = "\n".join(
                            text[decl.end():].split("\n")[:6])
                        if ORDERED.search(window):
                            resolved = True
                            break
                if resolved:
                    checked += 1
                    continue

                offenders.append(
                    f"{rel}:{line_no}: take({arg}) の前に並べ替えが無い "
                    f"— 受け側: {recv.strip()[-70:]!r}")

    print(f"truncation check: {checked} collection truncations ordered "
          f"/ {skipped_str} string truncations skipped")
    if offenders:
        print("TRUNCATION CHECK: FAILED", file=sys.stderr)
        for o in offenders:
            print("  " + o, file=sys.stderr)
        print("  上限を掛ける前に優先順位を付けること。順序を付けずに切ると、", file=sys.stderr)
        print("  一番深刻な項目が軽微な項目に押し出されて静かに消える", file=sys.stderr)
        print("  (RESEARCH-2026-08 §6 通知上限 / §12 警告上限が実例)。", file=sys.stderr)
        print("  順序が別経路 (DAO の ORDER BY 等) で保証されるなら、", file=sys.stderr)
        print("  直前行に `// truncate-order-ok: <理由>` を書いて根拠を残す。", file=sys.stderr)
        return 1
    print("TRUNCATION CHECK: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
