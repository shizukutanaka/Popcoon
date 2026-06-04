"""
test_stateful.py
Hypothesis Stateful testing — Trie を状態機械として扱い、任意の操作順序で不変条件を検証。

通常のテストは「単発の操作」を検証する。
Stateful は「操作の組み合わせ」から生じる状態不整合を暴く。

例: insert→insert→insert→suggest→insert→suggest の任意の順序で
    Trie が常に正しい状態を保つか。

これは手書きテストでは到底カバーできない空間を hypothesis が探索する。
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

import pytest
from hypothesis import strategies as st, settings, HealthCheck
from hypothesis.stateful import (
    RuleBasedStateMachine, rule, invariant, initialize, precondition,
)

from popcoon_core import Trie, AlertCondition, Product, Platform, eval_condition
from alert_optimizer import optimize, always_true, always_false, ConstantCondition


# ═══════════════════════════════════════════════════════════════════════════
# Trie 状態機械
# ═══════════════════════════════════════════════════════════════════════════
class TrieStateMachine(RuleBasedStateMachine):
    """Trie の操作列から不変条件を検証"""

    def __init__(self):
        super().__init__()
        self.trie = Trie()
        self.reference = set()   # 真の集合 (テスト oracle)

    # ── 操作ルール ─────────────────────────────────────────────────────────────
    @rule(word=st.text(min_size=1, max_size=15,
                       alphabet=st.characters(whitelist_categories=("Ll", "Nd"))))
    def insert_word(self, word):
        self.trie.insert(word)
        self.reference.add(word)

    @rule(prefix=st.text(min_size=0, max_size=5,
                         alphabet=st.characters(whitelist_categories=("Ll", "Nd"))))
    def suggest_prefix(self, prefix):
        result = self.trie.suggest(prefix, limit=10_000)
        # 結果の全要素は本当に挿入済み
        for r in result:
            assert r in self.reference, \
                f"未挿入の語が出現: {r!r}"
            assert r.startswith(prefix), \
                f"prefix={prefix!r} で {r!r} が返された"
        # 該当する挿入済み語の正しい集合とマッチ (limit 十分高い場合)
        expected = {w for w in self.reference if w.startswith(prefix)}
        if len(expected) <= 10_000:
            assert set(result) == expected, \
                f"suggest({prefix!r}) mismatch: got {len(set(result))}, expected {len(expected)}"

    # ── 不変条件 ─────────────────────────────────────────────────────────────
    @invariant()
    def size_matches_reference(self):
        assert self.trie.size() == len(self.reference), \
            f"Trie.size()={self.trie.size()} != reference={len(self.reference)}"

    @invariant()
    def all_inserted_findable(self):
        """全挿入済み語が、自身のprefixで検索可能"""
        for w in self.reference:
            if len(w) >= 1:
                suggestions = self.trie.suggest(w[:1], limit=100_000)
                assert w in suggestions, \
                    f"挿入済み {w!r} が prefix={w[:1]!r} で見つからない"


TestTrieStatefulCase = TrieStateMachine.TestCase
TestTrieStatefulCase.settings = settings(
    max_examples=30,
    stateful_step_count=20,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.differing_executors,
                           HealthCheck.filter_too_much],
    deadline=None,
)


# ═══════════════════════════════════════════════════════════════════════════
# AlertCondition 最適化の冪等性 — stateful
# ═══════════════════════════════════════════════════════════════════════════
class OptimizerStateMachine(RuleBasedStateMachine):
    """任意の条件ツリー構築 → 最適化の冪等性"""

    def __init__(self):
        super().__init__()
        self.tree = None

    @initialize()
    def setup(self):
        self.tree = AlertCondition(op="PRICE_BELOW", value=1000)

    @rule(value=st.integers(min_value=100, max_value=10000))
    def wrap_and_with_price_below(self, value):
        self.tree = AlertCondition(op="AND", children=[
            self.tree, AlertCondition(op="PRICE_BELOW", value=value),
        ])

    @rule(value=st.integers(min_value=100, max_value=10000))
    def wrap_or_with_price_above(self, value):
        self.tree = AlertCondition(op="OR", children=[
            self.tree, AlertCondition(op="PRICE_ABOVE", value=value),
        ])

    @rule()
    def wrap_not(self):
        self.tree = AlertCondition(op="NOT", children=[self.tree])

    # ── 不変条件 ─────────────────────────────────────────────────────────────
    @invariant()
    def optimize_is_idempotent(self):
        """optimize(x) の結果を再度 optimize しても変わらない"""
        if self.tree is None:
            return
        first = optimize(self.tree).condition
        if isinstance(first, AlertCondition):
            second = optimize(first).condition
            # 構造的等価性
            from test_golden_snapshots import canonicalize, snapshot_hash
            h1 = snapshot_hash(canonicalize(first))
            h2 = snapshot_hash(canonicalize(second))
            assert h1 == h2, f"冪等違反: {h1} != {h2}"

    @invariant()
    def optimize_preserves_semantics(self):
        """最適化前後で、サンプル商品に対する評価が一致"""
        if self.tree is None:
            return
        optimized = optimize(self.tree).condition
        # 4つのサンプル商品で検証
        samples = [
            Product("s1", "t", Platform.AMAZON, 500, 1000, 0, 50, None, "", None, 90),
            Product("s2", "t", Platform.AMAZON, 5000, 5500, 500, 0, None, "", None, 30),
            Product("s3", "t", Platform.RAKUTEN, 2000, 3000, 0, 100, None, "", None, 70),
            Product("s4", "t", Platform.YAHOO, 15000, 15000, 1000, 0, None, "", None, 60),
        ]
        for p in samples:
            orig_result = eval_condition(self.tree, p)
            opt_result = (eval_condition(optimized, p)
                          if isinstance(optimized, AlertCondition)
                          else optimized.value)
            assert orig_result == opt_result, \
                f"評価不一致: product={p.sku} orig={orig_result} opt={opt_result}"


TestOptimizerStatefulCase = OptimizerStateMachine.TestCase
TestOptimizerStatefulCase.settings = settings(
    max_examples=25,
    stateful_step_count=10,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.differing_executors],
    deadline=None,
)


if __name__ == "__main__":
    pytest.main([__file__, "-v", "--hypothesis-show-statistics"])
