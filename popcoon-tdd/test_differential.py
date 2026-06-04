"""
test_differential.py
Differential testing — 2つの独立実装が必ず同じ出力を返すか検証。

効果:
  - 片方にバグが入れば即座に発覚
  - hypothesis で膨大な入力空間を自動探索
  - 「Kotlin本体との整合性」の predecessor

naive = 明らかに正しいが遅い
optimized = 本番用だが微妙なバグが潜む可能性
両方同じ入力で同じ出力を返すことを hypothesis で確認する。
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

import pytest
from hypothesis import given, strategies as st, settings, HealthCheck, assume
from datetime import datetime, timedelta, timezone

from popcoon_core import (
    Trie, PriceRecord, Platform, Product,
    simulate_customs, calculate_tco, detect_dark_patterns,
    AlertCondition, eval_condition,
)
from naive_reference import (
    NaiveTrie, naive_simulate_customs, naive_calculate_tco,
    naive_detect_dark_patterns, naive_eval_condition,
)


# ═══════════════════════════════════════════════════════════════════════════
# Trie: 最適化版 vs linear search
# ═══════════════════════════════════════════════════════════════════════════
class TestTrieDifferential:

    @given(
        words=st.lists(
            st.text(min_size=1, max_size=15,
                    alphabet=st.characters(whitelist_categories=("Ll", "Nd"))),
            min_size=0, max_size=30),
        prefixes=st.lists(st.text(min_size=0, max_size=5,
                                   alphabet=st.characters(whitelist_categories=("Ll", "Nd"))),
                          min_size=1, max_size=3),
    )
    @settings(max_examples=50, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors,
        HealthCheck.filter_too_much])
    def test_suggest_equivalent(self, words, prefixes):
        """最適化 Trie と naive set-based Trie の結果集合が一致"""
        optimized = Trie()
        naive = NaiveTrie()
        for w in words:
            optimized.insert(w)
            naive.insert(w)

        for prefix in prefixes:
            opt_result = set(optimized.suggest(prefix, limit=100))
            naive_result = set(naive.suggest(prefix, limit=100))
            assert opt_result == naive_result, \
                f"prefix={prefix!r}: optimized={opt_result} naive={naive_result}"

    @given(words=st.lists(
        st.text(min_size=1, max_size=10,
                alphabet=st.characters(whitelist_categories=("Ll",))),
        min_size=0, max_size=50))
    @settings(max_examples=30, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors,
        HealthCheck.filter_too_much])
    def test_size_equivalent(self, words):
        """両実装のサイズが常に一致"""
        opt = Trie()
        naive = NaiveTrie()
        for w in words:
            opt.insert(w)
            naive.insert(w)
        assert opt.size() == naive.size()


# ═══════════════════════════════════════════════════════════════════════════
# 関税計算
# ═══════════════════════════════════════════════════════════════════════════
class TestCustomsDifferential:

    @given(
        price=st.integers(min_value=-10000, max_value=1_000_000),
        ship=st.integers(min_value=-1000, max_value=50_000),
        category=st.sampled_from([
            "衣類", "靴", "バッグ", "電子機器", "カメラ", "おもちゃ",
            "スポーツ用品", "化粧品", "食品", "その他", "unknown",
        ]),
    )
    @settings(max_examples=200, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_customs_outputs_identical(self, price, ship, category):
        """両実装の計算結果が完全一致"""
        opt = simulate_customs(price, ship, category)
        nav = naive_simulate_customs(price, ship, category)

        assert opt.foreign_price == nav["foreign_price"]
        assert opt.shipping_fee == nav["shipping_fee"]
        assert opt.dutiable_value == nav["dutiable_value"]
        assert opt.customs_duty == nav["customs_duty"]
        assert opt.consumption_tax == nav["consumption_tax"]
        assert opt.handling_fee == nav["handling_fee"]
        assert opt.total_landed_cost == nav["total_landed_cost"]
        assert opt.is_tax_exempt == nav["is_tax_exempt"]


# ═══════════════════════════════════════════════════════════════════════════
# TCO
# ═══════════════════════════════════════════════════════════════════════════
class TestTCODifferential:

    @given(
        price=st.integers(min_value=0, max_value=2_000_000),
        category=st.sampled_from([
            "inkjet_printer", "laser_printer", "coffee_capsule",
            "laptop", "smartphone", "refrigerator", "generic",
        ]),
        years=st.integers(min_value=1, max_value=20),
        intensity=st.floats(min_value=0.1, max_value=3.0, allow_nan=False),
    )
    @settings(max_examples=100, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_tco_outputs_identical(self, price, category, years, intensity):
        opt = calculate_tco(price, category, years, intensity)
        nav = naive_calculate_tco(price, category, years, intensity)

        assert opt.consumables_total == nav["consumables_total"], \
            f"consumables: opt={opt.consumables_total} nav={nav['consumables_total']}"
        assert opt.energy_total == nav["energy_total"]
        assert opt.maintenance == nav["maintenance"]
        assert opt.residual_value == nav["residual_value"]
        assert opt.total_tco == nav["total_tco"]
        assert opt.tco_per_month == nav["tco_per_month"]


# ═══════════════════════════════════════════════════════════════════════════
# ダークパターン検出
# ═══════════════════════════════════════════════════════════════════════════
class TestDarkPatternDifferential:

    @given(
        current=st.integers(min_value=0, max_value=100_000),
        list_price=st.one_of(st.none(), st.integers(min_value=0, max_value=100_000)),
        price_count=st.integers(min_value=0, max_value=60),
    )
    @settings(max_examples=80, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_detect_identical_warning_types(self, current, list_price, price_count):
        """両実装が検出する罠タイプが一致"""
        history = [
            PriceRecord("p", "amazon", 5000, 3000 + (i % 1000),
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i in range(price_count)
        ]
        opt_types = sorted(w.type.value for w in detect_dark_patterns(current, list_price, history))
        nav_types = sorted(w.type.value for w in naive_detect_dark_patterns(current, list_price, history))
        assert opt_types == nav_types, \
            f"罠タイプ不一致: opt={opt_types} nav={nav_types}"


# ═══════════════════════════════════════════════════════════════════════════
# AlertCondition 評価
# ═══════════════════════════════════════════════════════════════════════════
class TestEvalConditionDifferential:

    @staticmethod
    def _random_condition(rng, depth=0, max_depth=4):
        import random
        if depth >= max_depth or rng.random() < 0.45:
            op = rng.choice(["PRICE_BELOW", "PRICE_ABOVE", "FREE_SHIPPING",
                            "TRUST_AT_LEAST", "DISCOUNT_AT_LEAST"])
            if op in ("PRICE_BELOW", "PRICE_ABOVE"):
                val = rng.randint(0, 100000)
            elif op == "FREE_SHIPPING":
                val = rng.choice([True, False])
            else:
                val = rng.randint(0, 100)
            return AlertCondition(op=op, value=val)
        op = rng.choice(["AND", "OR", "NOT"])
        if op == "NOT":
            return AlertCondition(op="NOT", children=[
                TestEvalConditionDifferential._random_condition(rng, depth + 1, max_depth)
            ])
        n = rng.randint(1, 4)
        return AlertCondition(op=op, children=[
            TestEvalConditionDifferential._random_condition(rng, depth + 1, max_depth)
            for _ in range(n)
        ])

    def test_random_conditions_identical(self):
        """500個のランダム条件 + 5商品で両実装が同じ結果"""
        import random
        rng = random.Random(42)
        products = [
            Product("s1", "t", Platform.AMAZON, 500, 1000, 0, 50, None, "", None, 90),
            Product("s2", "t", Platform.AMAZON, 5000, 5500, 500, 0, None, "", None, 30),
            Product("s3", "t", Platform.RAKUTEN, 2000, 3000, 0, 100, None, "", None, 70),
            Product("s4", "t", Platform.YAHOO, 15000, 20000, 1000, 200, None, "", None, 60),
            Product("s5", "t", Platform.YAHOO, 0, 0, 0, 0, None, "", None, 100),
        ]
        mismatches = []
        for i in range(500):
            cond = self._random_condition(rng)
            for p in products:
                opt_result = eval_condition(cond, p)
                nav_result = naive_eval_condition(cond, p)
                if opt_result != nav_result:
                    mismatches.append((i, cond, p, opt_result, nav_result))
        assert not mismatches, \
            f"{len(mismatches)}/2500 不一致: 最初={mismatches[0] if mismatches else None}"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
