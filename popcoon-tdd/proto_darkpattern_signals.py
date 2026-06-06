"""Research prototype: UI-text dark-pattern detector (機能案: ダークパターン暴露).

BEAT_AMAZON_APP.md P1-6 / DARKPATTERN_EXPOSE_SPEC.md の実装。
学術 taxonomy（Mathur CSCW2019 / AidUI ICSE'23 / arXiv:2211.06543）の
**UIテキスト系**ダークパターン（偽の緊急性・希少性・操作的社会的証明・誤誘導・confirmshaming）を
オンデバイスのヒューリスティックで検出する純関数。価格・数値系は既存 Kotlin DarkPatternDetector が担当。

プライバシー: 商品ページの可視テキストのみを入力。送信なし・オンデバイス（I5 方針）。

※ Python 参照プロトタイプ。Kotlin 移植時は出力一致パリティテストを併設すること。
"""

import re
from typing import List, Optional

URGENCY = "URGENCY"
SCARCITY = "SCARCITY"
SOCIAL_PROOF = "SOCIAL_PROOF"
MISDIRECTION = "MISDIRECTION"
FORCED_ACTION = "FORCED_ACTION"

_URGENCY_PATTERNS = [
    r"残り\s*\d+\s*(?:時間|分|秒)",
    r"本日限り",
    r"今だけ",
    r"まもなく(?:終了|締切)",
    r"(?i)ending soon",
    r"(?i)limited[- ]time",
    r"(?i)\bhurry\b",
    r"(?i)act now",
    r"(?i)today only",
]
_SOCIAL_PROOF_PATTERNS = [
    r"\d+\s*人が[^。\n]{0,15}(?:見て|閲覧|カート|購入)",
    r"(?i)\d+\s+people are (?:viewing|looking)",
    r"(?i)in\s+\d+\s+carts",
]
_MISDIRECTION_PATTERNS = [
    r"(?:デフォルト|既定|初期設定)で(?:チェック|選択|追加)",
    r"(?i)pre-?(?:checked|selected)",
]
_CONFIRMSHAMING_PATTERNS = [
    r"いいえ.*(?:節約|お得|割引).*(?:したくない|不要|結構|いりません)",
    r"(?i)no,?\s+i\s+(?:don't|do not)\s+want\s+to\s+save",
]


def _first_match(text: str, patterns: List[str]) -> Optional[str]:
    for p in patterns:
        m = re.search(p, text)
        if m:
            return m.group(0)
    return None


def _detect_scarcity(text: str, stock_count: Optional[int]) -> Optional[dict]:
    m = re.search(r"残り\s*(\d+)\s*点", text)
    if m:
        n = int(m.group(1))
        return {"category": SCARCITY, "evidence": m.group(0),
                "severity": "HIGH" if n <= 3 else "MEDIUM"}
    if re.search(r"在庫わずか|残りわずか", text):
        ev = re.search(r"在庫わずか|残りわずか", text).group(0)
        return {"category": SCARCITY, "evidence": ev, "severity": "HIGH"}
    m = re.search(r"(?i)only\s+(\d+)\s+left", text)
    if m:
        n = int(m.group(1))
        return {"category": SCARCITY, "evidence": m.group(0),
                "severity": "HIGH" if n <= 3 else "MEDIUM"}
    if re.search(r"(?i)low (?:in )?stock", text):
        ev = re.search(r"(?i)low (?:in )?stock", text).group(0)
        return {"category": SCARCITY, "evidence": ev, "severity": "HIGH"}
    if stock_count is not None and 0 < stock_count <= 3:
        return {"category": SCARCITY, "evidence": f"stock_count={stock_count}",
                "severity": "HIGH"}
    return None


def detect_dark_patterns(text: str, stock_count: Optional[int] = None) -> List[dict]:
    """UIテキスト系ダークパターンの警告リストを返す（category 昇順、各カテゴリ最大1件）。"""
    t = text or ""
    warnings: List[dict] = []

    ev = _first_match(t, _URGENCY_PATTERNS)
    if ev:
        warnings.append({"category": URGENCY, "evidence": ev, "severity": "MEDIUM"})

    scar = _detect_scarcity(t, stock_count)
    if scar:
        warnings.append(scar)

    ev = _first_match(t, _SOCIAL_PROOF_PATTERNS)
    if ev:
        warnings.append({"category": SOCIAL_PROOF, "evidence": ev, "severity": "MEDIUM"})

    ev = _first_match(t, _MISDIRECTION_PATTERNS)
    if ev:
        warnings.append({"category": MISDIRECTION, "evidence": ev, "severity": "MEDIUM"})

    ev = _first_match(t, _CONFIRMSHAMING_PATTERNS)
    if ev:
        warnings.append({"category": FORCED_ACTION, "evidence": ev, "severity": "HIGH"})

    warnings.sort(key=lambda w: w["category"])
    return warnings
