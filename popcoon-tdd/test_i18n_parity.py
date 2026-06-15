"""
test_i18n_parity.py
4 ロケール (values / values-en / values-ko / values-zh-rCN) の strings.xml が
**キー集合**と**フォーマット指定子**で完全一致することを強制する。

なぜ必要か (ソクラテス監査 2026-06):
  これらは Android リソースであり、純関数テスト・パリティハーネスのどれも検証しない。
  片方のロケールにだけキーを足し忘れると、そのロケールでだけ実行時に
  Resources$NotFoundException でクラッシュする。さらに %1$d を %1$s と書き違えると
  IllegalFormatConversion で同様にクラッシュする。いずれもレビューや単体テストをすり抜ける。
  「いま一致している」状態を規律ではなくテストで固定する (Tier 45 の教訓)。
"""
import os
import re
import xml.etree.ElementTree as ET

import pytest

RES_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
LOCALES = ["values", "values-en", "values-ko", "values-zh-rCN"]
BASE = "values"

# 位置指定 %1$s / %2$d と、非位置 %s / %d の両方を拾う。%% (リテラル%) は除外。
_POSITIONAL = re.compile(r"%(\d+)\$([a-zA-Z])")
_SIMPLE = re.compile(r"%(?<!%%)([a-zA-Z])")


def _parse(locale):
    """locale の strings.xml を {name: text} で返す (translatable=false も含む)。"""
    path = os.path.join(RES_DIR, locale, "strings.xml")
    tree = ET.parse(path)
    out = {}
    for el in tree.getroot().findall("string"):
        name = el.get("name")
        # サブ要素 (xliff 等) を含み得るので itertext で全テキストを連結
        out[name] = "".join(el.itertext())
    return out


def _format_spec(text):
    """文字列のフォーマット指定子を正規化した集合で返す。

    位置指定子は {位置: 変換型} を 'p1:s' のように、非位置は 'n:d' を出現数つきで表す。
    これによりロケール間で「%1$d を %1$s と取り違え」「placeholder 抜け」を検出できる。
    """
    no_literal = text.replace("%%", "")
    specs = set()
    for pos, conv in _POSITIONAL.findall(no_literal):
        specs.add(f"p{pos}:{conv.lower()}")
    # 非位置指定子の個数 (位置指定を除いた残り)
    stripped = _POSITIONAL.sub("", no_literal)
    simple = re.findall(r"%([a-zA-Z])", stripped)
    for i, conv in enumerate(sorted(simple)):
        specs.add(f"n{i}:{conv.lower()}")
    return specs


@pytest.fixture(scope="module")
def parsed():
    return {loc: _parse(loc) for loc in LOCALES}


def test_all_locales_have_identical_key_sets(parsed):
    base_keys = set(parsed[BASE])
    for loc in LOCALES:
        if loc == BASE:
            continue
        keys = set(parsed[loc])
        missing = base_keys - keys
        extra = keys - base_keys
        assert not missing, f"{loc} に不足キー (実行時 NotFoundException): {sorted(missing)}"
        assert not extra, f"{loc} に余剰キー (base に無い): {sorted(extra)}"


def test_format_placeholders_match_across_locales(parsed):
    base = parsed[BASE]
    for loc in LOCALES:
        if loc == BASE:
            continue
        for key, text in parsed[loc].items():
            if key not in base:
                continue  # キー不一致は別テストが報告
            base_spec = _format_spec(base[key])
            loc_spec = _format_spec(text)
            assert base_spec == loc_spec, (
                f"{loc} のキー '{key}' のフォーマット指定子が base と不一致 "
                f"(実行時 IllegalFormatException 危険): base={sorted(base_spec)} {loc}={sorted(loc_spec)}"
            )


def test_no_locale_is_empty(parsed):
    for loc in LOCALES:
        assert len(parsed[loc]) > 0, f"{loc} の strings.xml が空"
