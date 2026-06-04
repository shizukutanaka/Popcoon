"""
test_alert_optimizer.py
AlertConditionOptimizer — TDD Red→Green→Refactor の完全実演。

要件:
1. 冗長な AND(x) = x に簡略化
2. 冗長な OR(x) = x に簡略化
3. NOT NOT x = x に簡略化
4. AND(x, y, x) = AND(x, y) (重複除去)
5. 矛盾検出 AND(PRICE_BELOW(100), PRICE_ABOVE(200)) = 常にfalse
6. 冗長条件吸収 AND(PRICE_BELOW(100), PRICE_BELOW(200)) = PRICE_BELOW(100)
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

import pytest
from popcoon_core import AlertCondition, Platform, Product, eval_condition
from alert_optimizer import (
    optimize,
    OptimizeResult,
    ConstantCondition,
    always_true,
    always_false,
)


def _leaf(op, value=None):
    return AlertCondition(op=op, value=value)


class TestOptimizerBasicReduction:
    """AND/ORツリーの基本的な簡略化"""

    def test_and_with_single_child_reduces_to_child(self):
        cond = AlertCondition(op="AND", children=[_leaf("PRICE_BELOW", 1000)])
        result = optimize(cond)
        assert result.condition == _leaf("PRICE_BELOW", 1000)
        assert result.changed is True

    def test_or_with_single_child_reduces_to_child(self):
        cond = AlertCondition(op="OR", children=[_leaf("PRICE_BELOW", 1000)])
        result = optimize(cond)
        assert result.condition == _leaf("PRICE_BELOW", 1000)

    def test_double_negation_cancels(self):
        inner = _leaf("PRICE_BELOW", 500)
        cond = AlertCondition(op="NOT",
                              children=[AlertCondition(op="NOT", children=[inner])])
        result = optimize(cond)
        assert result.condition == inner

    def test_leaf_unchanged(self):
        leaf = _leaf("PRICE_BELOW", 1000)
        result = optimize(leaf)
        assert result.condition == leaf
        assert result.changed is False


class TestOptimizerDeduplication:
    """重複条件の除去"""

    def test_and_removes_identical_duplicates(self):
        leaf = _leaf("PRICE_BELOW", 1000)
        cond = AlertCondition(op="AND", children=[leaf, leaf, leaf])
        result = optimize(cond)
        # 重複3つ → 1つに
        assert result.condition == leaf

    def test_or_removes_identical_duplicates(self):
        leaf = _leaf("FREE_SHIPPING", True)
        cond = AlertCondition(op="OR", children=[leaf, leaf])
        result = optimize(cond)
        assert result.condition == leaf


class TestOptimizerContradictionDetection:
    """矛盾: 必ずFalseになる条件"""

    def test_and_with_contradicting_price_bounds_is_false(self):
        # price <= 100 AND price >= 200 は矛盾
        cond = AlertCondition(op="AND", children=[
            _leaf("PRICE_BELOW", 100),
            _leaf("PRICE_ABOVE", 200),
        ])
        result = optimize(cond)
        assert always_false(result.condition)

    def test_and_with_x_and_not_x_is_false(self):
        leaf = _leaf("FREE_SHIPPING", True)
        cond = AlertCondition(op="AND", children=[
            leaf,
            AlertCondition(op="NOT", children=[leaf]),
        ])
        result = optimize(cond)
        assert always_false(result.condition)


class TestOptimizerRedundancyAbsorption:
    """冗長: より緩い条件を厳しい条件が吸収"""

    def test_and_with_two_price_below_keeps_stricter(self):
        # price <= 100 AND price <= 200 → price <= 100 (より厳しい方)
        cond = AlertCondition(op="AND", children=[
            _leaf("PRICE_BELOW", 100),
            _leaf("PRICE_BELOW", 200),
        ])
        result = optimize(cond)
        assert result.condition == _leaf("PRICE_BELOW", 100)

    def test_or_with_two_price_below_keeps_looser(self):
        # price <= 100 OR price <= 200 → price <= 200 (より緩い方)
        cond = AlertCondition(op="OR", children=[
            _leaf("PRICE_BELOW", 100),
            _leaf("PRICE_BELOW", 200),
        ])
        result = optimize(cond)
        assert result.condition == _leaf("PRICE_BELOW", 200)


class TestOptimizerTautology:
    """恒真: 必ずTrueになる条件"""

    def test_or_with_x_or_not_x_is_true(self):
        leaf = _leaf("FREE_SHIPPING", True)
        cond = AlertCondition(op="OR", children=[
            leaf,
            AlertCondition(op="NOT", children=[leaf]),
        ])
        result = optimize(cond)
        assert always_true(result.condition)

    def test_or_with_complementary_bounds_covers_all(self):
        # price <= 100 OR price >= 100 → 全ての価格をカバー (恒真の近似)
        # ただしPRICE_ABOVEとPRICE_BELOWの「>=100」「<=100」は境界で重複
        # 実装仕様: 両方が同じ境界値で、OR なら常にtrue
        cond = AlertCondition(op="OR", children=[
            _leaf("PRICE_BELOW", 100),
            _leaf("PRICE_ABOVE", 100),
        ])
        result = optimize(cond)
        assert always_true(result.condition)

    def test_or_with_always_true_child_becomes_true(self):
        """OPT06: OR(X, always_true, Y) = always_true の吸収"""
        # NOT(NOT(true)) = true を経由して恒真を作る
        # 直接的には constant を使う
        from alert_optimizer import ConstantCondition
        cond = AlertCondition(op="OR", children=[
            _leaf("PRICE_BELOW", 100),
            # 恒真な子: X OR NOT X で作る
            AlertCondition(op="OR", children=[
                _leaf("FREE_SHIPPING", True),
                AlertCondition(op="NOT",
                               children=[_leaf("FREE_SHIPPING", True)]),
            ]),
        ])
        result = optimize(cond)
        # 内側は true になり、外側のORは全体 true
        assert always_true(result.condition), \
            f"期待: ConstantCondition(True), 実際: {result.condition}"


class TestOptimizerNestedFlatten:
    """ネストした AND/OR の平坦化"""

    def test_nested_and_flattens(self):
        # AND(AND(a, b), c) → AND(a, b, c)
        a = _leaf("PRICE_BELOW", 1000)
        b = _leaf("FREE_SHIPPING", True)
        c = _leaf("TRUST_AT_LEAST", 70)
        nested = AlertCondition(op="AND", children=[
            AlertCondition(op="AND", children=[a, b]),
            c,
        ])
        result = optimize(nested)
        assert result.condition.op == "AND"
        assert len(result.condition.children) == 3

    def test_nested_or_flattens(self):
        a = _leaf("PRICE_BELOW", 1000)
        b = _leaf("FREE_SHIPPING", True)
        c = _leaf("TRUST_AT_LEAST", 70)
        nested = AlertCondition(op="OR", children=[
            AlertCondition(op="OR", children=[a, b]),
            c,
        ])
        result = optimize(nested)
        assert result.condition.op == "OR"
        assert len(result.condition.children) == 3


class TestOptimizerBehaviorPreservation:
    """最適化後の評価結果が元と完全に一致する"""

    def _product(self, **kwargs):
        defaults = dict(sku="s", title="t", platform=Platform.AMAZON,
                        real_price=1500, list_price=2000, shipping_fee=0,
                        points_back=50, trust_score=75)
        defaults.update(kwargs)
        return Product(**defaults)

    def test_optimized_condition_evaluates_same(self):
        products = [
            self._product(real_price=500, shipping_fee=0, trust_score=90),
            self._product(real_price=1500, shipping_fee=500, trust_score=60),
            self._product(real_price=5000, shipping_fee=0, trust_score=80),
        ]
        original = AlertCondition(op="AND", children=[
            AlertCondition(op="AND", children=[  # nested
                _leaf("PRICE_BELOW", 3000),
                _leaf("FREE_SHIPPING", True),
            ]),
            _leaf("TRUST_AT_LEAST", 70),
            _leaf("PRICE_BELOW", 3000),  # 重複
        ])
        optimized = optimize(original).condition
        for p in products:
            assert eval_condition(original, p) == eval_condition(optimized, p)


class TestOptimizerDeMorgan:
    """ド・モルガンの法則適用 (NOTの内側展開)"""

    def test_de_morgan_not_and(self):
        """NOT (A AND B) → (NOT A) OR (NOT B)"""
        a = _leaf("PRICE_BELOW", 1000)
        b = _leaf("FREE_SHIPPING", True)
        original = AlertCondition(op="NOT", children=[
            AlertCondition(op="AND", children=[a, b]),
        ])
        result = optimize(original)
        # 外側が OR に展開されていること
        assert result.condition.op == "OR"
        # 子が NOT(a), NOT(b) 相当
        assert len(result.condition.children) == 2

    def test_de_morgan_not_or(self):
        """NOT (A OR B) → (NOT A) AND (NOT B)"""
        a = _leaf("PRICE_BELOW", 1000)
        b = _leaf("FREE_SHIPPING", True)
        original = AlertCondition(op="NOT", children=[
            AlertCondition(op="OR", children=[a, b]),
        ])
        result = optimize(original)
        assert result.condition.op == "AND"
        assert len(result.condition.children) == 2


class TestOptimizerEmptyAndTrivial:
    """空・自明入力の扱い"""

    def test_empty_and_is_true(self):
        """AND() = 恒真 (恒等元)"""
        cond = AlertCondition(op="AND", children=[])
        result = optimize(cond)
        assert always_true(result.condition)

    def test_empty_or_is_false(self):
        """OR() = 恒偽"""
        cond = AlertCondition(op="OR", children=[])
        result = optimize(cond)
        assert always_false(result.condition)


class TestOptimizerChangedFlag:
    """changed フラグの正確性"""

    def test_leaf_not_changed(self):
        leaf = _leaf("PRICE_BELOW", 1000)
        assert optimize(leaf).changed is False

    def test_simple_and_no_reduction_not_changed(self):
        """AND(a, b) に簡略化要素なし"""
        cond = AlertCondition(op="AND", children=[
            _leaf("PRICE_BELOW", 1000),
            _leaf("FREE_SHIPPING", True),
        ])
        assert optimize(cond).changed is False

    def test_reducible_and_changed(self):
        cond = AlertCondition(op="AND", children=[
            _leaf("PRICE_BELOW", 1000),
            _leaf("PRICE_BELOW", 1000),  # 重複
        ])
        assert optimize(cond).changed is True


class TestOptimizerSemanticEquivalence:
    """プロパティ: ランダム条件ツリーで最適化前後の評価結果が全商品で一致"""

    import random as _random
    from hypothesis import given, strategies as st, settings, HealthCheck

    @staticmethod
    def _random_cond(rng, depth=0):
        """ランダム条件ツリー生成"""
        if depth > 3 or rng.random() < 0.5:
            # 葉ノード
            op = rng.choice(["PRICE_BELOW", "PRICE_ABOVE", "FREE_SHIPPING", "TRUST_AT_LEAST"])
            if op in ("PRICE_BELOW", "PRICE_ABOVE"):
                return _leaf(op, rng.randint(100, 10000))
            elif op == "FREE_SHIPPING":
                return _leaf(op, rng.choice([True, False]))
            else:
                return _leaf(op, rng.randint(0, 100))
        op = rng.choice(["AND", "OR", "NOT"])
        if op == "NOT":
            return AlertCondition(op="NOT",
                                  children=[TestOptimizerSemanticEquivalence._random_cond(rng, depth + 1)])
        n = rng.randint(1, 4)
        children = [TestOptimizerSemanticEquivalence._random_cond(rng, depth + 1) for _ in range(n)]
        return AlertCondition(op=op, children=children)

    @staticmethod
    def _random_product(rng):
        return Product(
            sku="s", title="t",
            platform=rng.choice([Platform.AMAZON, Platform.RAKUTEN, Platform.YAHOO]),
            real_price=rng.randint(0, 20000),
            list_price=rng.randint(500, 25000),
            shipping_fee=rng.choice([0, 300, 500, 800]),
            points_back=rng.randint(0, 500),
            trust_score=rng.randint(0, 100),
        )

    def _eval_any(self, c, product):
        """ConstantCondition と AlertCondition どちらも評価"""
        if isinstance(c, ConstantCondition):
            return c.value
        return eval_condition(c, product)

    def test_property_semantic_preservation(self):
        """100個のランダム条件 × 30商品 = 3000ケースで評価一致"""
        rng = TestOptimizerSemanticEquivalence._random.Random(42)
        mismatches = []
        for _ in range(100):
            cond = TestOptimizerSemanticEquivalence._random_cond(rng)
            optimized = optimize(cond).condition
            for _ in range(30):
                p = TestOptimizerSemanticEquivalence._random_product(rng)
                original_result = eval_condition(cond, p)
                optimized_result = self._eval_any(optimized, p)
                if original_result != optimized_result:
                    mismatches.append((cond, optimized, p, original_result, optimized_result))
        assert not mismatches, f"{len(mismatches)}件の評価不一致: 最初={mismatches[0]}"
