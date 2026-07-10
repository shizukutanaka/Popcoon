"""
naive_reference.py
最適化を一切考えない参照実装。
本体実装と常に同じ出力を返すことを検証する (differential testing)。

方針:
  - 性能は犠牲にして、明らかな正しさを優先
  - Python 標準ライブラリのみで素朴に書く
  - 可読性 > 速度
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')
from dataclasses import dataclass, field
from typing import List, Optional


# ═══════════════════════════════════════════════════════════════════════════
# Trie の naive 実装 — list ベース、ハッシュなし、再帰的
# ═══════════════════════════════════════════════════════════════════════════
class NaiveTrie:
    """全ての挿入語を単なる set に保存して linear search"""

    def __init__(self):
        self._words = set()

    def insert(self, word: str) -> None:
        if word:
            self._words.add(word)

    def suggest(self, prefix: str, limit: int = 8) -> List[str]:
        """linear search で prefix マッチを探す"""
        matches = []
        for w in self._words:
            if w.startswith(prefix):
                matches.append(w)
                if len(matches) >= limit:
                    break
        return matches

    def size(self) -> int:
        return len(self._words)


# ═══════════════════════════════════════════════════════════════════════════
# 関税計算の naive 実装 — 公式を忠実に展開
# ═══════════════════════════════════════════════════════════════════════════
def naive_simulate_customs(
    foreign_price_jpy: int,
    shipping_jpy: int,
    category: str = "その他",
    japan_best_price: Optional[int] = None,
):
    """税関ルールを素朴に実装"""
    # 負数ガード
    if foreign_price_jpy < 0:
        foreign_price_jpy = 0
    if shipping_jpy < 0:
        shipping_jpy = 0

    DUTY_RATES = {
        "衣類": 0.12, "靴": 0.30, "バッグ": 0.08,
        "電子機器": 0.00, "カメラ": 0.00, "おもちゃ": 0.00,
        "スポーツ用品": 0.04, "化粧品": 0.03, "食品": 0.20,
        "その他": 0.06,
    }
    THRESHOLD = 16_666

    dutiable = foreign_price_jpy + shipping_jpy
    is_exempt = (dutiable <= THRESHOLD)

    if is_exempt:
        duty = 0
        ctax = 0
        fee = 0
    else:
        rate = DUTY_RATES.get(category, DUTY_RATES["その他"])
        duty = int(dutiable * rate)
        # 軽減税率8% (食品) / 標準10% (それ以外) — popcoon_core.simulate_customs と同一。
        tax_rate = 0.08 if category == "食品" else 0.10
        ctax = int((dutiable + duty) * tax_rate)
        fee = 200

    total = foreign_price_jpy + shipping_jpy + duty + ctax + fee

    return {
        "foreign_price": foreign_price_jpy,
        "shipping_fee": shipping_jpy,
        "dutiable_value": dutiable,
        "customs_duty": duty,
        "consumption_tax": ctax,
        "handling_fee": fee,
        "total_landed_cost": total,
        "is_tax_exempt": is_exempt,
    }


# ═══════════════════════════════════════════════════════════════════════════
# AlertCondition 評価の naive 実装 — if/elif ベタ書き
# ═══════════════════════════════════════════════════════════════════════════
def naive_eval_condition(cond, product) -> bool:
    """最適化なしの素朴な評価"""
    if cond.op == "AND":
        for c in cond.children:
            if not naive_eval_condition(c, product):
                return False
        return True
    elif cond.op == "OR":
        for c in cond.children:
            if naive_eval_condition(c, product):
                return True
        return False
    elif cond.op == "NOT":
        return not naive_eval_condition(cond.children[0], product)
    elif cond.op == "PRICE_BELOW":
        return product.total_price <= cond.value
    elif cond.op == "PRICE_ABOVE":
        return product.total_price >= cond.value
    elif cond.op == "FREE_SHIPPING":
        expected = bool(cond.value)
        actual = (product.shipping_fee == 0)
        return actual == expected
    elif cond.op == "PLATFORM_IS":
        return product.platform == cond.value
    elif cond.op == "TRUST_AT_LEAST":
        return product.trust_score >= cond.value
    elif cond.op == "DISCOUNT_AT_LEAST":
        if product.list_price <= 0:
            return False
        pct = (product.list_price - product.real_price) * 100.0 / product.list_price
        return pct >= cond.value
    else:
        return False


# ═══════════════════════════════════════════════════════════════════════════
# TCO の naive 実装 — 表を展開
# ═══════════════════════════════════════════════════════════════════════════
def naive_calculate_tco(
    purchase_price: int,
    category: str,
    years: int = 5,
    intensity: float = 1.0,
):
    """全段階を素朴に列挙"""
    # 消耗品
    if category == "inkjet_printer":
        ink_black = 1800 * (6.0 * intensity)
        ink_color = 2200 * (4.0 * intensity)
        paper = 800 * (2.0 * intensity)
        consumables_yearly = int(ink_black) + int(ink_color) + int(paper)
    elif category == "laser_printer":
        toner = 6000 * (1.5 * intensity)
        drum = 8000 * 0.33
        paper = 600 * (3.0 * intensity)
        consumables_yearly = int(toner) + int(drum) + int(paper)
    elif category == "coffee_capsule":
        # optimized 側と同じ順序: int() を最後に
        consumables_yearly = int(80 * (365.0 * intensity))
    else:
        consumables_yearly = 0
    consumables_total = consumables_yearly * years

    # エネルギー
    energy_map = {
        "inkjet_printer": (15, 0.5),
        "laser_printer": (400, 0.5),
        "laptop": (45, 6.0),
        "refrigerator": (35, 24.0),
        "air_conditioner": (700, 8.0),
    }
    if category in energy_map:
        watts, hours = energy_map[category]
        energy_yearly = int(watts * hours * 365 / 1000 * 27)
    else:
        energy_yearly = 0
    energy_total = energy_yearly * years

    # 保守費
    if 4 <= years <= 6:
        maintenance = purchase_price // 10
    elif years >= 7:
        maintenance = purchase_price // 6
    else:
        maintenance = 0

    # 残存価値
    if category == "smartphone":
        rate = max(0.0, 0.5 - years * 0.12)
    elif category == "laptop":
        rate = max(0.0, 0.4 - years * 0.08)
    elif category == "inkjet_printer":
        rate = max(0.0, 0.05 - years * 0.01)
    else:
        rate = max(0.0, 0.05 - years * 0.01)
    residual = int(purchase_price * rate)

    tco = purchase_price + consumables_total + energy_total + maintenance - residual
    monthly = tco // (years * 12) if years > 0 else tco

    return {
        "purchase_price": purchase_price,
        "consumables_total": consumables_total,
        "energy_total": energy_total,
        "maintenance": maintenance,
        "residual_value": residual,
        "total_tco": tco,
        "tco_per_month": monthly,
    }


# ═══════════════════════════════════════════════════════════════════════════
# ダークパターン検出の naive 実装
# ═══════════════════════════════════════════════════════════════════════════
def naive_detect_dark_patterns(current_price, list_price, history):
    """ループで各パターンを素朴にチェック"""
    from popcoon_core import WarningType, Severity, PsychWarning
    warnings = []

    # 1. 常設セール
    if list_price is not None and list_price > current_price and len(history) >= 30:
        below_count = 0
        for r in history:
            if r.real_price < list_price:
                below_count += 1
        if below_count / len(history) > 0.90:
            warnings.append(PsychWarning(
                WarningType.ALWAYS_ON_DISCOUNT, "常設セール", Severity.HIGH))

    # 2. 参考価格詐欺
    if list_price is not None and len(history) > 0:
        max_real = max(r.real_price for r in history)
        if list_price > max_real * 1.5:
            warnings.append(PsychWarning(
                WarningType.INFLATED_LIST_PRICE, "参考価格誇張", Severity.HIGH))

    # 3. セール前値上げ
    if len(history) >= 14:
        recent_prices = [r.real_price for r in history[-7:]]
        prev_prices = [r.real_price for r in history[-14:-7]]
        recent_avg = sum(recent_prices) / 7
        prev_avg = sum(prev_prices) / 7
        if (recent_avg > prev_avg * 1.10 and list_price is not None
                and current_price < list_price):
            warnings.append(PsychWarning(
                WarningType.PRE_SALE_MARKUP, "セール前値上げ", Severity.HIGH))

    # 4. 端数価格
    last_two = current_price % 100
    if 80 <= last_two <= 99:
        warnings.append(PsychWarning(
            WarningType.CHARM_PRICING, "端数価格", Severity.LOW))

    return warnings
