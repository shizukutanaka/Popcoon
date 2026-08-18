#!/usr/bin/env python3
"""check_when_exhaustive.py — enum に対する `when` の網羅漏れを静的に検出する。

背景 (2026-08):
  app の 131 ファイルのうち実コンパイルできるのは 36 だけ (`run_compile_core.sh`)。
  残り 95 ファイル (Compose/Room/Hilt/ktor 依存) では、enum にエントリを足したときに
  `when` の更新を忘れても気付けない。Kotlin 2.x では enum に対する非網羅的な `when` は
  **エラー**なので、これは CI を有効化した瞬間に赤くなる欠陥クラスであり、
  しかも純粋に静的に決まる。

  実例: `DarkPatternTextDetector.Category` に OBSTRUCTION を追加したとき、
  `ui/DarkPatternTextLabels.kt` の `toLabelResource()` の `when` も同時に
  更新する必要があった。今回は同一コミットで直したが、UI 側 (コンパイル不能領域) に
  同種の `when` が増えれば同じ抜けが起こりうる。

`check_overrides.py` / `check_resources.py` と同じ位置づけ: Kotlin コンパイラの
代替ではなく、コンパイル不能領域まで含めて **特定の回帰クラスだけ** を塞ぐ。

健全性 (偽陽性を出さないための判定):
  - `else ->` があれば網羅済みとして対象外
  - 分岐ラベルが「ちょうど 1 つの enum のエントリ集合」に**すべて**含まれる場合だけ
    その enum に対する when と断定する
  - 候補 enum が 0 個または 2 個以上 (同名エントリを持つ enum が複数ある等) なら
    **断定せず skip** して件数だけ表示する
  - `->` の左辺に `,` 区切りの複数ラベル、`Enum.ENTRY` の修飾形も解釈する
"""
import collections
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC_DIRS = [ROOT / "app/src/main/java"]


def _matching_brace(text: str, open_idx: int) -> int:
    """text[open_idx] == '{' としてその対応する '}' の位置を返す。"""
    depth = 0
    i = open_idx
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(text) - 1


def _split_top_level(s: str, sep: str = ",") -> list[str]:
    """括弧・角括弧・波括弧の外にある区切り文字だけで分割する。"""
    out, depth, cur = [], 0, []
    for c in s:
        if c in "([{<":
            depth += 1
        elif c in ")]}>":
            depth -= 1
        if c == sep and depth <= 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    out.append("".join(cur))
    return [x.strip() for x in out if x.strip()]


def collect_enums(files) -> dict[str, list[set[str]]]:
    """enum 名 → エントリ名集合の **リスト**。

    単純名をキーにすると衝突する (`PointSimulator.Kind` と `SaleEvent.Kind`、
    `WidgetVerdict` と `CustomsSimulator` の `Verdict` 等)。単一の dict に入れると
    後勝ちで上書きされ、実在する enum が「候補 none」として黙って検査対象から
    外れてしまう。同名を全部保持し、照合側で一意性を判定する。
    """
    enums: dict[str, list[set[str]]] = collections.defaultdict(list)
    for _, text in files:
        for m in re.finditer(r"\benum class\s+([A-Za-z_]\w*)", text):
            brace = text.find("{", m.end())
            if brace < 0:
                continue
            body = text[brace + 1:_matching_brace(text, brace)]
            head = _split_top_level(body, ";")[0] if ";" in body else body
            entries = set()
            for chunk in _split_top_level(head, ","):
                # 行コメント・ブロックコメント・アノテーションを除去して先頭識別子を取る
                chunk = re.sub(r"//[^\n]*", " ", chunk)
                chunk = re.sub(r"/\*.*?\*/", " ", chunk, flags=re.S)
                chunk = re.sub(r"@\w+(\([^)]*\))?", " ", chunk).strip()
                em = re.match(r"([A-Za-z_]\w*)", chunk)
                if em and em.group(1).isupper() or (em and re.fullmatch(r"[A-Z][A-Za-z0-9_]*", em.group(1))):
                    entries.add(em.group(1))
            if entries and entries not in enums[m.group(1)]:
                enums[m.group(1)].append(entries)
    return dict(enums)


def main() -> int:
    files = []
    for d in SRC_DIRS:
        for f in sorted(d.rglob("*.kt")):
            files.append((str(f.relative_to(ROOT)), io.open(f, encoding="utf-8").read()))

    enums = collect_enums(files)
    errors, checked, skipped = [], 0, []

    for path, text in files:
        for m in re.finditer(r"\bwhen\s*\(", text):
            brace = text.find("{", m.end())
            if brace < 0:
                continue
            # `when (x) {` の間に改行を挟んだ別ブロックを拾わないよう、近接だけ見る
            if "\n" in text[m.end():brace] and text[m.end():brace].count("\n") > 2:
                continue
            body = text[brace + 1:_matching_brace(text, brace)]
            if re.search(r"^\s*else\s*->", body, re.M):
                continue
            # Kotlin は分岐ラベルを複数行に分けて書ける:
            #     Kind.SPRING,
            #     Kind.SUMMER,
            #     Kind.WINTER -> ...
            # 行単位で `->` を探すと最後の行しか見えず、残りを「未処理」と誤報する
            # (SaleCalendarLabels.descRes() で実際に偽陽性が出た)。
            # カンマ改行を畳んでから走査する。畳んだ結果 `->` を含まない行は
            # そもそも分岐として拾われないので、RHS 側の多行引数には影響しない。
            body = re.sub(r",[ \t]*\n\s*", ", ", body)
            labels: set[str] = set()
            qualifiers: set[str] = set()
            ok = True
            for line in re.finditer(r"^\s*([^\n]*?)\s*->", body, re.M):
                lhs = line.group(1).strip()
                if not lhs or "in " in lhs or "is " in lhs:
                    ok = False
                    break
                for part in _split_top_level(lhs):
                    lm = re.fullmatch(r"((?:[A-Za-z_]\w*\.)*)([A-Z][A-Z0-9_]*)", part)
                    if not lm:
                        ok = False
                        break
                    labels.add(lm.group(2))
                    # `Outer.Severity.LOW` のような修飾形なら enum 名が直前の区切りに出る
                    segs = [s for s in lm.group(1).split(".") if s]
                    if segs:
                        qualifiers.add(segs[-1])
                if not ok:
                    break
            if not ok or not labels:
                continue
            # 修飾形で enum 名が判っているならそれで絞る (HIGH/LOW/MEDIUM のように
            # 単純名だけでは Confidence / Severity / Trust を区別できないケースを救う)。
            pool = {n: es for n, es in enums.items() if n in qualifiers} or enums
            candidates = [(n, e) for n, es in pool.items() for e in es if labels <= e]
            # 候補が複数でもエントリ集合が同一なら「不足しているもの」は一意に決まる。
            distinct = {frozenset(e) for _, e in candidates}
            if len(distinct) != 1:
                line_no = text[:m.start()].count("\n") + 1
                skipped.append(f"{path}:{line_no} (labels={sorted(labels)}, "
                               f"candidates={sorted({n for n, _ in candidates}) or 'none'})")
                continue
            name = "/".join(sorted({n for n, _ in candidates}))
            checked += 1
            missing = sorted(set(next(iter(distinct))) - labels)
            if missing:
                line_no = text[:m.start()].count("\n") + 1
                errors.append(
                    f"{path}:{line_no}: enum {name} に対する when が網羅していない "
                    f"(未処理: {', '.join(missing)}) — else 節も無い")

    # 何を見送ったかは必ず出す (黙って対象を減らすと「全部見た」と誤読される)。
    print(f"when-exhaustiveness check: {len(enums)} enums / {checked} enum-when checked"
          f" / {len(skipped)} skipped (enum を一意に特定できず)")
    for s in skipped:
        print(f"  [skip] {s}")
    if errors:
        print("WHEN EXHAUSTIVENESS: FAILED", file=sys.stderr)
        for e in errors:
            print("  " + e, file=sys.stderr)
        return 1
    print("WHEN EXHAUSTIVENESS: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
