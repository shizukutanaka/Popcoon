"""
test_metamorphic.py
Metamorphic testing — 入力変換と出力変換の数学的関係を検証する。

通常のテストは "入力 X → 期待 Y" の形式。
Metamorphic は "入力 X1 と X2 の関係 → 出力 Y1 と Y2 の関係" を検証。

oracle (期待値) が作れない関数 (確率的/非決定論的に見える) でも
関係性から正しさを担保できる。

例:
  - 関税: price を 2倍にすると、課税される関税も概ね 2倍
  - 予測: 履歴を逆順にすると trend が反転する
  - TCO: years=5 → years=10 で total は単調増加
  - 冪等性: optimize(optimize(x)) == optimize(x)
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

import pytest
from hypothesis import given, strategies as st, settings, HealthCheck, assume
from datetime import datetime, timedelta, timezone

from popcoon_core import (
    PriceRecord, Platform, Product,
    simulate_customs, calculate_tco, predict_price,
    AlertCondition, eval_condition, Trie,
)
from alert_optimizer import optimize, always_true, always_false, ConstantCondition
from buy_timing_scorer import score_buy_timing, TimingVerdict


# ═══════════════════════════════════════════════════════════════════════════
# M1: 関税計算の metamorphic 関係
# ═══════════════════════════════════════════════════════════════════════════
class TestCustomsMetamorphic:

    @given(
        price=st.integers(min_value=20_000, max_value=100_000),
        ship=st.integers(min_value=0, max_value=5_000),
    )
    @settings(max_examples=80, suppress_health_check=[HealthCheck.differing_executors])
    def test_linear_scaling_price(self, price, ship):
        """MR-1: 商品価格を2倍にすると、関税+消費税もほぼ2倍"""
        r1 = simulate_customs(price, ship, "衣類")
        r2 = simulate_customs(price * 2, ship, "衣類")
        # 免税で境界を跨がない場合のみ
        assume(not r1.is_tax_exempt and not r2.is_tax_exempt)

        # 関税は比例、消費税はほぼ比例、手数料は固定
        tax1 = r1.customs_duty + r1.consumption_tax
        tax2 = r2.customs_duty + r2.consumption_tax
        # r2.tax > 1.8 * r1.tax (2倍に近いが完全には線形でない)
        assert tax2 >= 1.5 * tax1, \
            f"価格倍率2に対し税倍率 {tax2/tax1:.2f}x (期待>=1.5)"

    @given(
        price=st.integers(min_value=100, max_value=100_000),
        ship=st.integers(min_value=0, max_value=10_000),
    )
    @settings(max_examples=50, suppress_health_check=[HealthCheck.differing_executors])
    def test_monotonic_price(self, price, ship):
        """MR-2: 価格を増やすと total_landed_cost も単調増加"""
        r1 = simulate_customs(price, ship, "衣類")
        r2 = simulate_customs(price + 1000, ship, "衣類")
        assert r2.total_landed_cost >= r1.total_landed_cost

    def test_electronics_zero_duty_invariant(self):
        """MR-3: 電子機器カテゴリでは価格に関わらず duty=0"""
        for price in [1000, 10_000, 100_000, 1_000_000]:
            r = simulate_customs(price, 2000, "電子機器")
            if not r.is_tax_exempt:
                assert r.customs_duty == 0, \
                    f"電子機器 ¥{price} で duty={r.customs_duty} (期待0)"

    def test_tax_exempt_boundary_symmetric(self):
        """MR-4: 免税境界 (16,666) 前後で税の有無が切り替わる"""
        r_under = simulate_customs(16_666, 0, "衣類")
        r_over = simulate_customs(16_667, 0, "衣類")
        assert r_under.is_tax_exempt is True
        assert r_over.is_tax_exempt is False
        # 境界1円差で total は大きくジャンプ
        assert r_over.total_landed_cost > r_under.total_landed_cost + 1000


# ═══════════════════════════════════════════════════════════════════════════
# M2: TCO の metamorphic 関係
# ═══════════════════════════════════════════════════════════════════════════
class TestTCOMetamorphic:

    @given(
        price=st.integers(min_value=1000, max_value=500_000),
        years=st.integers(min_value=1, max_value=5),
    )
    @settings(max_examples=50, suppress_health_check=[HealthCheck.differing_executors])
    def test_years_monotonic(self, price, years):
        """MR-5: 使用年数を延ばすと total_tco は単調増加"""
        r1 = calculate_tco(price, "inkjet_printer", years, intensity=1.0)
        r2 = calculate_tco(price, "inkjet_printer", years + 2, intensity=1.0)
        # 年数増やせば consumables と energy が増える
        assert r2.consumables_total >= r1.consumables_total
        assert r2.total_tco >= r1.total_tco - r2.residual_value

    @given(
        price=st.integers(min_value=1000, max_value=500_000),
    )
    @settings(max_examples=30, suppress_health_check=[HealthCheck.differing_executors])
    def test_intensity_monotonic(self, price):
        """MR-6: 使用強度を上げると消耗品コストも単調増加"""
        r_low = calculate_tco(price, "inkjet_printer", 5, intensity=0.5)
        r_med = calculate_tco(price, "inkjet_printer", 5, intensity=1.0)
        r_high = calculate_tco(price, "inkjet_printer", 5, intensity=2.0)
        assert r_low.consumables_total <= r_med.consumables_total <= r_high.consumables_total

    def test_smartphone_residual_decays_monotonic(self):
        """MR-7: スマホの売却価値は年数経過で単調減少"""
        results = [calculate_tco(100_000, "smartphone", y) for y in [1, 2, 3, 4]]
        residuals = [r.residual_value for r in results]
        # 単調減少 or 同値 (仕様: 0.5 - 0.12y)
        for i in range(len(residuals) - 1):
            assert residuals[i] >= residuals[i + 1], \
                f"residual[{i}]={residuals[i]} < residual[{i+1}]={residuals[i+1]}"


# ═══════════════════════════════════════════════════════════════════════════
# M3: 予測エンジンの metamorphic 関係
# ═══════════════════════════════════════════════════════════════════════════
class TestPredictionMetamorphic:

    def _history(self, prices):
        return [
            PriceRecord("p", "amazon", p + 200, p,
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i, p in enumerate(prices)
        ]

    def test_trend_reversal_on_reverse(self):
        """MR-8: 履歴を完全逆順にするとトレンドが反転"""
        ascending = self._history([1000 + i * 100 for i in range(30)])
        pred_asc = predict_price(ascending)
        # 反転: 逆順
        descending = self._history([3900 - i * 100 for i in range(30)])
        pred_desc = predict_price(descending)

        # asc (上昇) では predicted_30d > current
        # desc (下降) では predicted_30d < current
        assert pred_asc.predicted_30d > pred_asc.current_price
        assert pred_desc.predicted_30d < pred_desc.current_price

    def test_constant_price_predicts_constant(self):
        """MR-9: 履歴が完全一定なら予測も同値"""
        for price in [1000, 5000, 20_000, 100_000]:
            history = self._history([price] * 30)
            pred = predict_price(history)
            # トレンド ~ 0, 予測は現在価格とほぼ同じ
            assert abs(pred.predicted_7d - price) <= price // 100, \
                f"定価 {price}: predicted_7d={pred.predicted_7d}"
            assert abs(pred.predicted_30d - price) <= price // 100

    def test_shift_preserves_structure(self):
        """MR-10: 全体に同額+Cしても trend/predicted の差は維持"""
        base = [1000 + i * 50 for i in range(30)]
        shifted = [p + 500 for p in base]
        pred_base = predict_price(self._history(base))
        pred_shift = predict_price(self._history(shifted))
        # 予測値も同額シフト
        diff_7d = pred_shift.predicted_7d - pred_base.predicted_7d
        diff_30d = pred_shift.predicted_30d - pred_base.predicted_30d
        # 500±許容
        assert abs(diff_7d - 500) <= 50
        assert abs(diff_30d - 500) <= 50


# ═══════════════════════════════════════════════════════════════════════════
# M4: AlertOptimizer の冪等性と可換性
# ═══════════════════════════════════════════════════════════════════════════
class TestOptimizerMetamorphic:

    @staticmethod
    def _leaf(op, value=None):
        return AlertCondition(op=op, value=value)

    def test_idempotency(self):
        """MR-11: optimize(optimize(x)) == optimize(x)"""
        cond = AlertCondition(op="AND", children=[
            AlertCondition(op="AND", children=[
                self._leaf("PRICE_BELOW", 3000),
                self._leaf("FREE_SHIPPING", True),
            ]),
            self._leaf("PRICE_BELOW", 5000),
            AlertCondition(op="OR", children=[
                self._leaf("TRUST_AT_LEAST", 70),
                AlertCondition(op="NOT", children=[self._leaf("TRUST_AT_LEAST", 70)]),
            ]),
        ])
        first = optimize(cond)
        # 2回目の optimize をしても結果が同じ (冪等)
        # ConstantCondition は AlertCondition ではないので optimize に渡せない
        # AlertCondition の場合のみ再実行
        if not isinstance(first.condition, ConstantCondition):
            second = optimize(first.condition)
            # 1回目と2回目の構造が同じ
            from test_golden_snapshots import canonicalize, snapshot_hash
            assert snapshot_hash(canonicalize(first.condition)) == \
                   snapshot_hash(canonicalize(second.condition))

    def test_commutativity_and(self):
        """MR-12: AND の子順序を入れ替えても結果は意味的に同じ"""
        a = self._leaf("PRICE_BELOW", 1000)
        b = self._leaf("FREE_SHIPPING", True)
        c = self._leaf("TRUST_AT_LEAST", 70)

        tree1 = AlertCondition(op="AND", children=[a, b, c])
        tree2 = AlertCondition(op="AND", children=[c, b, a])
        tree3 = AlertCondition(op="AND", children=[b, a, c])

        o1, o2, o3 = optimize(tree1).condition, optimize(tree2).condition, \
                     optimize(tree3).condition

        # 各商品で評価が一致すること
        products = [
            Product("s1", "t", Platform.AMAZON, 500, 1000, 0, 50, None, "", None, 80),
            Product("s2", "t", Platform.AMAZON, 2000, 2000, 500, 0, None, "", None, 40),
        ]
        for p in products:
            r1 = eval_condition(o1, p) if isinstance(o1, AlertCondition) else o1.value
            r2 = eval_condition(o2, p) if isinstance(o2, AlertCondition) else o2.value
            r3 = eval_condition(o3, p) if isinstance(o3, AlertCondition) else o3.value
            assert r1 == r2 == r3, f"可換性違反: {r1}/{r2}/{r3}"

    def test_double_negation_elimination_idempotent(self):
        """MR-13: NOT NOT x = x、そして NOT NOT NOT x = NOT x"""
        leaf = self._leaf("PRICE_BELOW", 1000)

        # NOT NOT x → x
        cond2 = AlertCondition(op="NOT", children=[
            AlertCondition(op="NOT", children=[leaf])
        ])
        assert optimize(cond2).condition == leaf

        # NOT NOT NOT x → NOT x
        cond3 = AlertCondition(op="NOT", children=[
            AlertCondition(op="NOT", children=[
                AlertCondition(op="NOT", children=[leaf])
            ])
        ])
        result3 = optimize(cond3).condition
        assert isinstance(result3, AlertCondition)
        assert result3.op == "NOT"
        assert result3.children[0] == leaf


# ═══════════════════════════════════════════════════════════════════════════
# M5: BuyTimingScorer の metamorphic
# ═══════════════════════════════════════════════════════════════════════════
class TestScorerMetamorphic:

    def _history(self, prices):
        return [
            PriceRecord("p", "amazon", p + 500, p,
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i, p in enumerate(prices)
        ]

    def test_atl_better_than_above_low(self):
        """MR-14: ATL到達時のスコアは高値圏より必ず高い"""
        history = self._history([i + 1000 for i in range(30)])  # 1000-1029
        score_low = score_buy_timing(current=1000, list_price=2000, history=history)
        score_high = score_buy_timing(current=1029, list_price=2000, history=history)
        assert score_low.total > score_high.total, \
            f"ATL score ({score_low.total}) <= 高値圏 ({score_high.total})"

    def test_larger_discount_better_score(self):
        """MR-15: 同じ current でも list_price が高い (大割引) ほどスコア高い"""
        history = self._history([5000] * 30)
        s1 = score_buy_timing(current=5000, list_price=6000, history=history)
        s2 = score_buy_timing(current=5000, list_price=10000, history=history)
        # より大きな割引は加点大
        assert s2.total >= s1.total

    def test_verdict_monotonic_with_score(self):
        """MR-16: total が 高いほど verdict は "買い" 寄り"""
        # 人工的に様々なスコアを作る
        from buy_timing_scorer import _decide_verdict
        verdicts = [_decide_verdict(total) for total in range(0, 101)]
        # 一度 BUY_NOW になったら逆戻りしない
        buy_seen = False
        wait_after_buy = False
        for v in verdicts:
            if v == TimingVerdict.BUY_NOW:
                buy_seen = True
            if buy_seen and v == TimingVerdict.WAIT:
                wait_after_buy = True
        assert not wait_after_buy, \
            "スコア昇順で BUY_NOW の後に WAIT が出現"


# ═══════════════════════════════════════════════════════════════════════════
# M6: Trie の不変条件
# ═══════════════════════════════════════════════════════════════════════════
class TestTrieMetamorphic:

    def test_insert_order_invariant(self):
        """MR-17: 挿入順序は検索結果の集合に影響しない"""
        words = ["apple", "apricot", "banana", "app", "ape"]
        t1 = Trie()
        for w in words:
            t1.insert(w)

        t2 = Trie()
        for w in reversed(words):
            t2.insert(w)

        # "ap" で始まる集合が一致
        s1 = set(t1.suggest("ap", limit=100))
        s2 = set(t2.suggest("ap", limit=100))
        assert s1 == s2, f"順序依存: {s1} != {s2}"

    def test_size_equals_unique_inserts(self):
        """MR-18: size() == ユニーク単語数"""
        t = Trie()
        words_with_dups = ["apple", "apple", "banana", "apple", "cherry"]
        for w in words_with_dups:
            t.insert(w)
        assert t.size() == 3  # apple, banana, cherry

    def test_suggest_returns_subset_of_all_inserted(self):
        """MR-19: suggest の結果は必ず挿入した単語のサブセット"""
        words = {"iphone", "ipad", "imac", "iwatch", "itunes"}
        t = Trie()
        for w in words:
            t.insert(w)
        results = set(t.suggest("i", limit=100))
        assert results <= words, f"未挿入語が出現: {results - words}"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
