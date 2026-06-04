"""
alert_optimizer.py
AlertCondition ツリーの最適化 (純粋関数)。

戦略:
  1. 再帰的に子をまず最適化
  2. 平坦化 (ネストしたAND/ORを展開)
  3. 重複除去
  4. 矛盾・恒真の検出と定数化
  5. 冗長条件吸収 (より厳しい/緩い方を残す)
"""
from dataclasses import dataclass
from typing import Optional
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')
from popcoon_core import AlertCondition


# ── 定数条件 (恒真/恒偽) ────────────────────────────────────────────────────
@dataclass
class ConstantCondition:
    """always True or False"""
    value: bool

    def __eq__(self, other):
        return isinstance(other, ConstantCondition) and self.value == other.value

    def __hash__(self):
        return hash(("const", self.value))


def always_true(c) -> bool:
    return isinstance(c, ConstantCondition) and c.value is True


def always_false(c) -> bool:
    return isinstance(c, ConstantCondition) and c.value is False


@dataclass
class OptimizeResult:
    condition: object  # AlertCondition or ConstantCondition
    changed: bool


# ── エントリポイント ──────────────────────────────────────────────────────
def optimize(cond: AlertCondition) -> OptimizeResult:
    original_hash = _structural_hash(cond)
    optimized = _optimize_recursive(cond)
    new_hash = _structural_hash(optimized)
    return OptimizeResult(condition=optimized, changed=(original_hash != new_hash))


def _structural_hash(c) -> str:
    """構造比較用の正規化ハッシュ"""
    if isinstance(c, ConstantCondition):
        return f"const:{c.value}"
    if isinstance(c, AlertCondition):
        child_hashes = sorted(_structural_hash(ch) for ch in c.children)
        return f"{c.op}({c.value}|{','.join(child_hashes)})"
    return str(c)


def _optimize_recursive(c):
    if not isinstance(c, AlertCondition):
        return c

    # 空 AND/OR は恒等元
    if not c.children:
        if c.op == "AND":
            return ConstantCondition(True)
        if c.op == "OR":
            return ConstantCondition(False)
        return c

    # 子を再帰的に最適化
    new_children = [_optimize_recursive(ch) for ch in c.children]

    # NOT の処理
    if c.op == "NOT":
        inner = new_children[0]
        # NOT NOT x = x
        if isinstance(inner, AlertCondition) and inner.op == "NOT":
            return inner.children[0]
        # NOT(true) = false, NOT(false) = true
        if always_true(inner):
            return ConstantCondition(False)
        if always_false(inner):
            return ConstantCondition(True)
        # ド・モルガン: NOT(AND(a,b)) → OR(NOT a, NOT b)
        if isinstance(inner, AlertCondition) and inner.op in ("AND", "OR"):
            new_op = "OR" if inner.op == "AND" else "AND"
            negated = [_optimize_recursive(
                AlertCondition(op="NOT", children=[ch])
            ) for ch in inner.children]
            return _optimize_boolean(new_op, negated)
        return AlertCondition(op="NOT", children=[inner])

    # AND / OR の処理
    if c.op in ("AND", "OR"):
        return _optimize_boolean(c.op, new_children)

    return AlertCondition(op=c.op, children=new_children, value=c.value)


def _optimize_boolean(op: str, children: list):
    """AND/OR の最適化"""
    # 1. 平坦化: AND(AND(a,b), c) → AND(a,b,c)
    flat = []
    for ch in children:
        if isinstance(ch, AlertCondition) and ch.op == op:
            flat.extend(ch.children)
        else:
            flat.append(ch)

    # 2. 定数吸収
    # AND(..., false, ...) = false;  AND(..., true, ...) は true を除去
    # OR(..., true, ...) = true;      OR(..., false, ...) は false を除去
    if op == "AND":
        if any(always_false(c) for c in flat):
            return ConstantCondition(False)
        flat = [c for c in flat if not always_true(c)]
    else:  # OR
        if any(always_true(c) for c in flat):
            return ConstantCondition(True)
        flat = [c for c in flat if not always_false(c)]

    # 3. 重複除去 (構造ハッシュで)
    seen = {}
    for ch in flat:
        key = _structural_hash(ch)
        if key not in seen:
            seen[key] = ch
    flat = list(seen.values())

    # 4. X AND NOT X = false; X OR NOT X = true
    negations = _find_complementary_pair(flat)
    if negations:
        return ConstantCondition(False if op == "AND" else True)

    # 5. 価格境界の矛盾/吸収
    flat = _optimize_price_bounds(op, flat)
    if len(flat) == 1 and isinstance(flat[0], ConstantCondition):
        return flat[0]

    # 6. 単一子なら展開
    if len(flat) == 1:
        return flat[0]

    # 7. 空なら定数
    if not flat:
        return ConstantCondition(True if op == "AND" else False)

    return AlertCondition(op=op, children=flat)


def _find_complementary_pair(children) -> bool:
    """X と NOT(X) のペアを見つける"""
    positive = set()
    negative = set()
    for ch in children:
        if isinstance(ch, AlertCondition) and ch.op == "NOT":
            negative.add(_structural_hash(ch.children[0]))
        else:
            positive.add(_structural_hash(ch))
    return bool(positive & negative)


def _optimize_price_bounds(op: str, children: list) -> list:
    """PRICE_BELOW/ABOVE の矛盾検出と吸収"""
    result = []
    below_bounds = []  # (value, node)
    above_bounds = []

    for ch in children:
        if isinstance(ch, AlertCondition) and ch.op == "PRICE_BELOW":
            below_bounds.append((ch.value, ch))
        elif isinstance(ch, AlertCondition) and ch.op == "PRICE_ABOVE":
            above_bounds.append((ch.value, ch))
        else:
            result.append(ch)

    if op == "AND":
        # 矛盾: below_max < above_min
        if below_bounds and above_bounds:
            max_below = max(v for v, _ in below_bounds)
            min_above = min(v for v, _ in above_bounds)
            if max_below < min_above:
                return [ConstantCondition(False)]
        # 吸収: 最小のbelow (厳しい)、最大のabove (厳しい)
        if below_bounds:
            result.append(min(below_bounds, key=lambda x: x[0])[1])
        if above_bounds:
            result.append(max(above_bounds, key=lambda x: x[0])[1])
    else:  # OR
        # 境界一致で全体カバー: PRICE_BELOW(N) OR PRICE_ABOVE(N) = true
        below_values = {v for v, _ in below_bounds}
        above_values = {v for v, _ in above_bounds}
        if below_values & above_values:
            return [ConstantCondition(True)]
        # 吸収: 最大のbelow (緩い)、最小のabove (緩い)
        if below_bounds:
            result.append(max(below_bounds, key=lambda x: x[0])[1])
        if above_bounds:
            result.append(min(above_bounds, key=lambda x: x[0])[1])

    return result
