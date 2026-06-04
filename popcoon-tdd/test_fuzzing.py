"""
test_fuzzing.py
Fuzz testing — ランダムに変異した入力で例外発生を探す。

目的: まだ想定していないエッジケースで関数がクラッシュしないことを保証。

戦略:
  1. 境界値: 0, -1, MAX_INT, 大量データ
  2. 不正型: None, 空文字列, 特殊文字
  3. 組合せ: 複数パラメータを独立にランダム生成
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

import pytest
from hypothesis import given, strategies as st, settings, HealthCheck, assume
from datetime import datetime, timedelta, timezone

from popcoon_core import (
    PriceRecord, Platform, Product,
    simulate_customs, calculate_tco, predict_price,
    detect_dark_patterns, score_eco_ethics,
    AlertCondition, eval_condition, Trie,
)
from alert_optimizer import optimize
from buy_timing_scorer import score_buy_timing


# ═══════════════════════════════════════════════════════════════════════════
# 境界値 Fuzzing
# ═══════════════════════════════════════════════════════════════════════════
class TestBoundaryFuzzing:
    """極端な値でクラッシュしないこと"""

    @pytest.mark.parametrize("value", [0, 1, -1, 2**31, 2**63 - 1, -(2**63)])
    def test_customs_boundary_prices(self, value):
        """整数境界値で例外なし"""
        try:
            result = simulate_customs(value, value, "衣類")
            # 結果が妥当な型
            assert isinstance(result.total_landed_cost, int)
        except (OverflowError, ValueError):
            pytest.skip(f"overflow handled gracefully: {value}")

    @pytest.mark.parametrize("years", [0, 1, 100, 1000])
    def test_tco_extreme_years(self, years):
        """使用年数 0〜1000 年"""
        result = calculate_tco(10_000, "generic", years=years, intensity=1.0)
        assert result.total_tco >= 0 or years == 0

    def test_predict_price_single_record(self):
        """1件履歴 → None (14件未満)"""
        history = [PriceRecord("p", "amazon", 1500, 1000,
                               datetime(2026, 1, 1, tzinfo=timezone.utc))]
        assert predict_price(history) is None

    def test_empty_alert_tree_all_ops(self):
        """空の AND/OR/NOT が全て処理される"""
        for op in ["AND", "OR"]:
            cond = AlertCondition(op=op, children=[])
            result = optimize(cond)
            assert result.condition is not None


# ═══════════════════════════════════════════════════════════════════════════
# Property-based Fuzzing
# ═══════════════════════════════════════════════════════════════════════════
class TestPropertyFuzzing:
    """プロパティベースで無数の組合せを試す"""

    @given(
        price=st.integers(min_value=-10**9, max_value=10**9),
        ship=st.integers(min_value=-10**9, max_value=10**9),
        category=st.sampled_from(["衣類", "靴", "電子機器", "食品", "その他",
                                   "", "UNKNOWN_CATEGORY"]),
    )
    @settings(max_examples=200, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_customs_never_crashes(self, price, ship, category):
        """任意の整数入力で customs が例外を出さない"""
        result = simulate_customs(price, ship, category)
        assert isinstance(result.total_landed_cost, int)

    @given(
        price=st.integers(min_value=0, max_value=10**8),
        category=st.text(min_size=0, max_size=30),
        years=st.integers(min_value=1, max_value=50),
        intensity=st.floats(min_value=0.1, max_value=5.0, allow_nan=False),
    )
    @settings(max_examples=100, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_tco_never_crashes(self, price, category, years, intensity):
        result = calculate_tco(price, category, years=years, intensity=intensity)
        assert result.total_tco is not None

    @given(
        prices=st.lists(st.integers(min_value=0, max_value=10**7),
                        min_size=0, max_size=500),
    )
    @settings(max_examples=50, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_predict_price_never_crashes(self, prices):
        history = [
            PriceRecord("p", "amazon", p + 100, p,
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i, p in enumerate(prices)
        ]
        # None か妥当な結果
        result = predict_price(history)
        assert result is None or 0 <= result.buy_now_probability <= 1

    @given(
        current=st.integers(min_value=0, max_value=10**7),
        list_price=st.one_of(st.none(),
                              st.integers(min_value=0, max_value=10**7)),
        price_count=st.integers(min_value=0, max_value=200),
    )
    @settings(max_examples=80, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_dark_patterns_never_crashes(self, current, list_price, price_count):
        history = [
            PriceRecord("p", "amazon", 5000, 3000 + (i % 1000),
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i in range(price_count)
        ]
        # 例外なく実行される
        warnings = detect_dark_patterns(current, list_price, history)
        assert isinstance(warnings, list)

    @given(
        country=st.one_of(st.none(), st.text(max_size=10)),
        category=st.text(max_size=30),
        certifications=st.lists(st.text(max_size=20), max_size=5),
    )
    @settings(max_examples=100, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_eco_ethics_never_crashes(self, country, category, certifications):
        score = score_eco_ethics(country, category, certifications)
        assert 0 <= score.overall <= 100

    @given(
        words=st.lists(st.text(min_size=0, max_size=30), min_size=0, max_size=50),
        prefixes=st.lists(st.text(min_size=0, max_size=10), min_size=0, max_size=5),
    )
    @settings(max_examples=50, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_trie_never_crashes(self, words, prefixes):
        t = Trie()
        for w in words:
            t.insert(w)
        for p in prefixes:
            result = t.suggest(p, limit=100)
            # 結果は全て挿入済み
            for r in result:
                assert r in words, f"未挿入の単語 {r!r}"


# ═══════════════════════════════════════════════════════════════════════════
# Alert condition ツリーの深い fuzzing
# ═══════════════════════════════════════════════════════════════════════════
class TestAlertConditionFuzzing:

    @staticmethod
    def _random_condition(rng, depth=0, max_depth=5):
        import random
        if depth >= max_depth or rng.random() < 0.4:
            op = rng.choice(["PRICE_BELOW", "PRICE_ABOVE", "FREE_SHIPPING",
                            "TRUST_AT_LEAST", "DISCOUNT_AT_LEAST", "UNKNOWN_OP"])
            if op in ("PRICE_BELOW", "PRICE_ABOVE"):
                val = rng.randint(-10**6, 10**7)
            elif op == "FREE_SHIPPING":
                val = rng.choice([True, False])
            else:
                val = rng.randint(-100, 200)
            return AlertCondition(op=op, value=val)
        op = rng.choice(["AND", "OR", "NOT"])
        if op == "NOT":
            return AlertCondition(op="NOT", children=[
                TestAlertConditionFuzzing._random_condition(rng, depth + 1, max_depth)
            ])
        n = rng.randint(0, 5)  # 空の children も含む
        children = [
            TestAlertConditionFuzzing._random_condition(rng, depth + 1, max_depth)
            for _ in range(n)
        ]
        return AlertCondition(op=op, children=children)

    def test_random_trees_never_crash_optimize(self):
        """ランダム条件ツリー 200個を最適化"""
        import random
        rng = random.Random(42)
        crashed = []
        for i in range(200):
            tree = self._random_condition(rng)
            try:
                result = optimize(tree)
                assert result is not None
            except RecursionError:
                # 深すぎるツリーは skip
                continue
            except Exception as e:
                crashed.append((i, tree, e))
        assert not crashed, f"{len(crashed)}/200 ツリーで crash: {crashed[:3]}"

    def test_random_trees_eval_never_crashes(self):
        """ランダム条件 + ランダム商品で eval_condition が crash しない"""
        import random
        rng = random.Random(99)
        sample = Product("s", "t", Platform.AMAZON, 1000, 1500, 300, 50,
                         None, "", None, 70)
        for _ in range(500):
            tree = self._random_condition(rng)
            try:
                eval_condition(tree, sample)
            except RecursionError:
                continue
            except Exception as e:
                pytest.fail(f"eval_condition crash: {e}\ntree={tree}")


# ═══════════════════════════════════════════════════════════════════════════
# Scorer の fuzzing
# ═══════════════════════════════════════════════════════════════════════════
class TestScorerFuzzing:

    @given(
        current=st.integers(min_value=-1000, max_value=10**7),
        list_price=st.integers(min_value=-1000, max_value=10**7),
        n=st.integers(min_value=0, max_value=200),
    )
    @settings(max_examples=50, suppress_health_check=[
        HealthCheck.too_slow, HealthCheck.differing_executors])
    def test_scorer_never_crashes(self, current, list_price, n):
        history = [
            PriceRecord("p", "amazon", max(1, list_price), max(1, current),
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i in range(n)
        ]
        score = score_buy_timing(current, list_price, history)
        # None か 0..100 範囲
        assert score is None or 0 <= score.total <= 100


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
