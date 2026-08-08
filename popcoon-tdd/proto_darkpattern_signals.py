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
HIDDEN_SUBSCRIPTION = "HIDDEN_SUBSCRIPTION"
OBSTRUCTION = "OBSTRUCTION"

# 緊急性の煽り。消費者庁 2026-06-18 意識調査で「過去1年に経験した」類型の **最多** が
# 緊急性の強調 (回答者の 76.2% が何らかのダークパターンを目撃、37.5% が経験)。
# recall を広げる一方、正当な販促表示との重なりが大きい語は意図的に **入れない**:
#  - 「期間限定」: 「期間限定フレーバー」のように商品属性を指す用法が多く誤爆源。
#  - 裸の「最終日」: 「最終日までにお届け」等の配送文脈を拾う。本日/セール/販売で限定する。
#  - 日単位のカウンタ: 「あと5日で発送」は納期であって煽りではない (時間/分/秒に限定)。
_URGENCY_PATTERNS = [
    # 「あと」は「残り」と同義の接頭辞。従来は残りのみで「あと3時間」を取りこぼしていた
    # (SCARCITY 側は在庫助数詞で あと に対応済みだったが URGENCY 側は未対応だった)。
    r"(?:残り|あと)\s*\d+\s*(?:時間|分|秒)",
    r"本日限り",
    r"今だけ",
    r"まもなく(?:終了|締切)",
    r"(?:終了|締切|締め切り)間近",
    r"売り切れ次第終了",
    r"(?:本日|セール|販売)最終日",
    r"(?i)ending soon",
    r"(?i)limited[- ]time",
    r"(?i)\bhurry\b",
    r"(?i)act now",
    r"(?i)today only",
    r"(?i)last chance",
    r"(?i)don'?t miss out",
    r"(?i)offer ends",
    r"(?i)final hours?",
    r"(?i)while supplies last",
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
# 隠れ定期購入 (subscription trap)。継続を強制/自動化する語に限定 (中立表記は拾わない)。
_HIDDEN_SUBSCRIPTION_PATTERNS = [
    r"定期(?:購入|便|コース|縛り)",
    r"\d+\s*回(?:以上)?[^。\n]{0,6}(?:継続|受け取り|購入)が(?:条件|必須|必要)",
    r"自動(?:更新|継続|課金)",
    r"(?i)auto[-\s]?renew(?:s|al|ing)?",
    r"(?i)automatically\s+renews?",
    r"(?i)recurring\s+(?:billing|charge|payment|subscription)",
]

# 解約妨害 (OECD 2022 taxonomy の "Obstruction" / いわゆるローチモーテル)。
# HIDDEN_SUBSCRIPTION が「契約が継続すること自体を隠す」類型なのに対し、こちらは
# 「契約後に抜けにくくする」類型で、規制上も別物として扱われる (2026-08 リサーチ):
#  - 消費者庁「デジタル取引・特定商取引法等検討会」第4回 (2026-04) が
#    「契約・解約場面における規律の在り方」を独立論点として審議。中間とりまとめは 2026 夏。
#  - 特商法 2022-06 施行の最終確認画面義務の下でも、「いつでも解約可能」と表示しつつ
#    実際は「次回発送の N 日前までに電話で連絡」を課す相談が国民生活センター等に継続。
#
# 二段階の深刻度:
#  HIGH   = 解約手段を電話に限定 (相談事例で「電話が繋がらない」が最頻出の実害)
#  MEDIUM = 次回発送日起点の事前連絡期限 (実効的な解約可能期間を圧縮する条件)
#
# 誤爆ガード: 「のみ/だけ/に限」等の限定語を必須にし、「解約は電話またはマイページから」
# のような複数手段の提示は拾わない。期限側も「解約/連絡/申し出」等の解約文脈語を
# 後続に要求し、「次回お届け日の変更は3日前まで」のような無関係な期限を除外する。
_OBSTRUCTION_PHONE_ONLY_PATTERNS = [
    r"(?:解約|退会|定期[^。\n]{0,4}(?:停止|解除))[^。\n]{0,12}(?:お)?電話[^。\n]{0,6}(?:のみ|だけ|に限)",
    r"(?:お)?電話[^。\n]{0,6}(?:のみ|だけ)[^。\n]{0,12}(?:解約|退会)",
    r"(?i)cancel(?:lation)?[^.\n]{0,20}by\s+phone\s+only",
    r"(?i)call\s+(?:us\s+)?to\s+cancel",
]
_OBSTRUCTION_DEADLINE_PATTERNS = [
    r"次回[^。\n]{0,10}(?:発送|お届け|配送)[^。\n]{0,10}\d+\s*日前まで"
    r"[^。\n]{0,12}(?:解約|退会|停止|キャンセル|連絡|申し出|申出)",
    r"(?i)cancel[^.\n]{0,40}\d+\s*days?\s+before",
]


def _first_match(text: str, patterns: List[str]) -> Optional[str]:
    for p in patterns:
        m = re.search(p, text)
        if m:
            return m.group(0)
    return None


def _detect_scarcity(text: str, stock_count: Optional[int]) -> Optional[dict]:
    # 在庫カウンタ: 「残り/あと N 点/個/セット/台」。以前は「点」限定で、同義の
    # 「残り3個」「あと2セット」を取りこぼしていた (点以外の助数詞は法的に等価な
    # 在庫煽り)。時間系 (残り3時間) は URGENCY 側で拾うため助数詞を在庫系に限定。
    m = re.search(r"(?:残り|あと)\s*(\d+)\s*(?:点|個|セット|台)", text)
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


def _detect_obstruction(text: str) -> Optional[dict]:
    """解約妨害。電話限定 (HIGH) を事前連絡期限 (MEDIUM) より優先する。"""
    ev = _first_match(text, _OBSTRUCTION_PHONE_ONLY_PATTERNS)
    if ev:
        return {"category": OBSTRUCTION, "evidence": ev, "severity": "HIGH"}
    ev = _first_match(text, _OBSTRUCTION_DEADLINE_PATTERNS)
    if ev:
        return {"category": OBSTRUCTION, "evidence": ev, "severity": "MEDIUM"}
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

    ev = _first_match(t, _HIDDEN_SUBSCRIPTION_PATTERNS)
    if ev:
        warnings.append({"category": HIDDEN_SUBSCRIPTION, "evidence": ev, "severity": "HIGH"})

    obs = _detect_obstruction(t)
    if obs:
        warnings.append(obs)

    warnings.sort(key=lambda w: w["category"])
    return warnings
