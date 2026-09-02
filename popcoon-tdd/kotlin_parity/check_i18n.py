#!/usr/bin/env python3
"""check_i18n.py — 4 ロケールの翻訳整合を検査する (キー数一致より強い不変条件)。

背景 (2026-08):
  既存の i18n ゲート (`ci/verify.py`) は **キーの個数**しか見ていない。
  個数が同じでも、ja に A / en に B があれば通ってしまうし、
  書式指定子がずれていれば `getString(id, args)` が **実行時に例外**を投げる。
  「4 ロケール完全一致」は ASSESSMENT が長所として掲げている性質なので、
  その主張に見合う検査にする。

検査:
  (a) キー**集合**が 4 ロケールで一致するか (個数一致より強い)
  (b) 位置指定子 (`%1$d` 等) が全ロケールで一致するか
      — ずれると IllegalFormatException / MissingFormatArgumentException で落ちる。
        裸の `%`(「4% 追加」等) は書式適用されない文字列で正当に使われているので
        **位置指定子だけ**を見る (ここを緩めないと誤検出が出て、ゲートが信用されなくなる)
  (c) 位置指定子を持つ文字列に未エスケープの `%` が混ざっていないか
      — こちらは確実にクラッシュする
  (d) 原文 (日本語) の混入。かなは中/韓/英に現れないので、かなの残留 = 未翻訳。
      **漢字では判定しない** — 中国語と日本語は漢字を共有しており、
      「重要」「保存」「警告」は両言語で正しい (初期実装はここで 13 件を誤報した)
  (e) 英語ロケールに CJK が残っていないか
"""
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
BASE = "values"
LOCALES = ["values-en", "values-ko", "values-zh-rCN"]

STRING = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.S)
POSITIONAL = re.compile(r"%\d+\$[-+ #0]*\d*(?:\.\d+)?[a-zA-Z]")
# かな (中/韓/英には現れない)。漢字は中日で共有するので使わない。
KANA = re.compile(r"[ぁ-ゖァ-ヺ]")
CJK = re.compile(r"[぀-ヿ一-鿿]")

errors: list[str] = []


def load(locale: str) -> dict[str, str]:
    path = RES / locale / "strings.xml"
    return {m.group(1): m.group(2) for m in STRING.finditer(io.open(path, encoding="utf-8").read())}


def main() -> int:
    base = load(BASE)
    for locale in LOCALES:
        d = load(locale)

        # (a) キー集合
        missing, extra = set(base) - set(d), set(d) - set(base)
        for k in sorted(missing):
            errors.append(f"{locale}: キー '{k}' が無い (ja にはある)")
        for k in sorted(extra):
            errors.append(f"{locale}: キー '{k}' は ja に無い")

        for k in sorted(set(base) & set(d)):
            ja_v, tr_v = base[k], d[k]

            # (b) 位置指定子の一致
            a, b = sorted(POSITIONAL.findall(ja_v)), sorted(POSITIONAL.findall(tr_v))
            if a != b:
                errors.append(
                    f"{locale}/{k}: 位置指定子が不一致 ja={a} vs {b} "
                    f"(getString(id, args) が実行時例外を投げる)")

            # (c) 位置指定子を持つのに未エスケープの % がある
            for lname, v in ((BASE, ja_v), (locale, tr_v)):
                stripped = v.replace("%%", "")
                if POSITIONAL.search(stripped) and re.search(r"%(?!\d+\$)", stripped):
                    errors.append(
                        f"{lname}/{k}: 位置指定子を含む文字列に未エスケープの '%' がある "
                        f"({v!r}) — %% にすること")

            # (d) 原文の混入 (かな)
            if KANA.search(tr_v):
                errors.append(f"{locale}/{k}: 日本語のかなが残っている ({tr_v[:40]!r}) — 未翻訳")

            # (e) 英語ロケールの CJK
            if locale == "values-en" and CJK.search(tr_v):
                errors.append(f"{locale}/{k}: 英語ロケールに CJK が残っている ({tr_v[:40]!r})")

    print(f"i18n check: {len(base)} keys × {len(LOCALES) + 1} locales "
          f"(キー集合 / 位置指定子 / 原文混入)")
    if errors:
        print("I18N CHECK: FAILED", file=sys.stderr)
        for e in errors[:40]:
            print("  " + e, file=sys.stderr)
        if len(errors) > 40:
            print(f"  ... 他 {len(errors) - 40} 件", file=sys.stderr)
        return 1
    print("I18N CHECK: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
