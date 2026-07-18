"""内容量/重量属性の抽出 — ProductMatcher 不一致ペナルティ拡張のオラクル.

ProductMatcher は既に個数 (個/本) と色の不一致を減点するが、液体の内容量 (ml/L)
や重量 (g/kg) の食い違いは見ていなかった。「洗剤 500ml」と「洗剤 1L」、
「コーヒー豆 200g」と「500g」は別 SKU (別価格) だが型番/タイトルが似ていると
同一商品として名寄せされてしまう。

内容量を正準基準単位に正規化して比較する:
  - 液体: ml を基準 (1L / 1リットル / 1ℓ = 1000ml)
  - 重量: **mg** を基準 (1g = 1000mg, 1kg = 1,000,000mg)。基準を g にすると
    サプリの 500mg が 0.5g→丸め 0 になり 300mg と衝突するため、最小単位の mg で
    保持して小数丸めを排除する。
液体と重量は別ドメインなので (ml と mg を混同しない)、タプル (domain, base_amount)
を返し、ドメインが一致しかつ量が食い違う場合のみ「不一致」とみなす。domain は
"ml" (液体) / "g" (重量) のラベルで、base_amount の単位はそれぞれ ml / mg。

誤抽出対策 (この種の軽量ヒューリスティクスで最も危険な点):
  - 素の「g」(グラム) は **小文字のみ** で拾う。ネットワーク世代の「5G」「4G」は
    大文字 G なので拾わない (NFKC は幅正規化のみで大小は変えない)。
  - 素の「ミリ」は拾わない (「5ミリ」は通常 5mm=長さで内容量ではない)。ミリリットルは
    「ミリリットル」全体でのみ拾う。
  - 複数の異なる量が現れる場合 (「500ml×2本 計1L」等) は曖昧なので None (中立)。

既存の extractQuantity/extractColor と同じ保守方針 (両方から一意に取れて食い違う
場合のみ減点)。Kotlin ProductMatcher と厳密一致させ、kotlin_parity/run_matcher.sh が
実 Kotlin 実行で照合する。
"""

from __future__ import annotations

import re
import unicodedata

# 液体量: 数字(小数可) + 単位。大小無視で安全 (5G のような一般的誤爆源が無い)。
# 長い単位を先に (ミリリットル を リットル より優先)。
_LIQUID_RE = re.compile(
    r"(\d+(?:\.\d+)?)\s*(ミリリットル|リットル|ml|cc|ℓ|l)", re.IGNORECASE
)
# 重量: 素の「g」はネットワーク「5G」誤爆を避けるため小文字のみ (IGNORECASE 不使用)。
# kg/mg は大小両方許容 (誤爆源が無い)。長い単位を先に。
_WEIGHT_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(グラム|キロ|[kK][gG]|[mM][gG]|kg|g)")

_LIQUID_FACTORS = {
    "ミリリットル": 1.0,
    "ml": 1.0,
    "cc": 1.0,
    "リットル": 1000.0,
    "l": 1000.0,
    "ℓ": 1000.0,
}
# 基準は mg (最小単位)。小数を作らないため丸め衝突が起きない。
_WEIGHT_FACTORS = {
    "mg": 1.0,
    "g": 1000.0,
    "グラム": 1000.0,
    "kg": 1_000_000.0,
    "キロ": 1_000_000.0,
}


def _liquid_factor(unit: str) -> float:
    return _LIQUID_FACTORS[unit.lower()]


def _weight_factor(unit: str) -> float:
    return _WEIGHT_FACTORS[unit.lower()]


def _amounts(title: str, pattern: re.Pattern, factor_of) -> set[int]:
    """タイトルから (正規化済み) 基準単位量の集合を取り出す。丸め誤差回避のため round(int)."""
    out: set[int] = set()
    for num, unit in pattern.findall(title):
        out.add(round(float(num) * factor_of(unit)))
    return out


def extract_volume(title: str):
    """内容量/重量を (domain, base_amount) で返す。無い/曖昧なら None.

    domain: "ml" (液体) または "g" (重量)。
    液体と重量が両方1つずつ出た場合は曖昧とみなし None (どちらを SKU 差の基準に
    すべきか判断できないため保守的に中立)。
    """
    norm = unicodedata.normalize("NFKC", title)
    liquids = _amounts(norm, _LIQUID_RE, _liquid_factor)
    weights = _amounts(norm, _WEIGHT_RE, _weight_factor)

    liquid = next(iter(liquids)) if len(liquids) == 1 else None
    weight = next(iter(weights)) if len(weights) == 1 else None

    if liquid is not None and weight is None:
        return ("ml", liquid)
    if weight is not None and liquid is None:
        return ("g", weight)
    return None


def volume_mismatch(title_a: str, title_b: str) -> bool:
    """両タイトルから同ドメインの内容量が取れて、値が食い違うなら True."""
    va = extract_volume(title_a)
    vb = extract_volume(title_b)
    if va is None or vb is None:
        return False
    return va[0] == vb[0] and va[1] != vb[1]
