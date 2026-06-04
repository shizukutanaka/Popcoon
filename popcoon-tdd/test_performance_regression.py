"""
test_performance_regression.py
パフォーマンス回帰検出テスト。

明示的な閾値を持つ — 誰かが不用意にリファクタして遅くしたら CI で止まる。
閾値は保守的に設定: 現行実装 × 3倍 を上限とする。

目標:
  - Trie.suggest 100k件: ≤ 50ms
  - predict_price 1000件: ≤ 5ms
  - simulate_customs: ≤ 100μs
  - calculate_tco: ≤ 100μs
  - detect_dark_patterns 100件: ≤ 500μs
  - optimize 複雑ツリー: ≤ 1ms
  - score_buy_timing 30件: ≤ 5ms
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

import time
import statistics
from datetime import datetime, timedelta, timezone

import pytest

from popcoon_core import (
    Trie, PriceRecord, Platform, predict_price,
    simulate_customs, calculate_tco, detect_dark_patterns,
    AlertCondition, Product,
)
from alert_optimizer import optimize
from buy_timing_scorer import score_buy_timing


def measure_median_us(fn, iterations=100, warmup=10):
    """中央値で測定 — 外れ値に強い"""
    # ウォームアップ
    for _ in range(warmup):
        fn()
    samples = []
    for _ in range(iterations):
        start = time.perf_counter_ns()
        fn()
        samples.append((time.perf_counter_ns() - start) / 1000)  # ns → μs
    return statistics.median(samples)


class TestTriePerformance:
    """Trie のパフォーマンス閾値"""

    def test_suggest_100k_under_50ms(self):
        """100k件で suggest が 50ms 以内"""
        t = Trie()
        for i in range(100_000):
            t.insert(f"id_{i:06d}")
        median_us = measure_median_us(
            lambda: t.suggest("id_0", limit=100),
            iterations=20,
        )
        threshold_us = 50_000  # 50ms
        assert median_us < threshold_us, \
            f"Trie.suggest(100k) 中央値 {median_us:.0f}μs > 閾値 {threshold_us}μs"
        print(f"\n  Trie.suggest(100k): {median_us:.0f}μs (閾値 {threshold_us}μs)")

    def test_insert_under_10us_per_call(self):
        """insert 1回 ≤ 10μs"""
        t = Trie()
        median_us = measure_median_us(
            lambda: t.insert(f"word_{time.perf_counter_ns()}"),
            iterations=1000,
        )
        threshold = 10
        assert median_us < threshold, \
            f"Trie.insert 中央値 {median_us:.2f}μs > 閾値 {threshold}μs"
        print(f"\n  Trie.insert: {median_us:.2f}μs")

    def test_suggest_empty_prefix_under_1ms(self):
        """空prefixで10k件中の limit件返す (全Trie走査のため保守的閾値)"""
        t = Trie()
        for i in range(10_000):
            t.insert(f"item_{i:05d}")
        # CI環境のノイズを吸収するため iterations/warmup 増加
        median_us = measure_median_us(
            lambda: t.suggest("", limit=10),
            iterations=200,
            warmup=50,
        )
        threshold = 1_000  # 1ms — CI マシン変動を吸収
        assert median_us < threshold, \
            f"Trie.suggest('') {median_us:.0f}μs > {threshold}μs"
        print(f"\n  Trie.suggest empty: {median_us:.2f}μs")


class TestPredictionPerformance:

    def test_predict_1000_records_under_5ms(self):
        records = [
            PriceRecord(
                product_key="p", platform="amazon",
                list_price=1200, real_price=1000 + (i % 500),
                recorded_at=datetime(2026, 1, 1, tzinfo=timezone.utc)
                            + timedelta(days=i),
            ) for i in range(1000)
        ]
        median_us = measure_median_us(lambda: predict_price(records), iterations=50)
        threshold = 5_000
        assert median_us < threshold, \
            f"predict_price(1000) {median_us:.0f}μs > {threshold}μs"
        print(f"\n  predict_price(1000): {median_us:.0f}μs")

    def test_predict_30_records_under_500us(self):
        records = [
            PriceRecord(
                product_key="p", platform="amazon",
                list_price=1500, real_price=1000,
                recorded_at=datetime(2026, 1, 1, tzinfo=timezone.utc)
                            + timedelta(days=i),
            ) for i in range(30)
        ]
        median_us = measure_median_us(lambda: predict_price(records), iterations=200)
        threshold = 500
        assert median_us < threshold
        print(f"\n  predict_price(30): {median_us:.2f}μs")


class TestCustomsPerformance:

    def test_simulate_under_10us(self):
        median_us = measure_median_us(
            lambda: simulate_customs(20_000, 3_000, "衣類", 25_000),
            iterations=5000,
        )
        threshold = 10
        assert median_us < threshold
        print(f"\n  simulate_customs: {median_us:.3f}μs")


class TestTCOPerformance:

    def test_calculate_tco_under_100us(self):
        median_us = measure_median_us(
            lambda: calculate_tco(50_000, "inkjet_printer", 5, intensity=1.0),
            iterations=1000,
        )
        threshold = 100
        assert median_us < threshold
        print(f"\n  calculate_tco: {median_us:.2f}μs")


class TestDarkPatternPerformance:

    def test_detect_100_records_under_500us(self):
        history = [
            PriceRecord(
                product_key="p", platform="amazon",
                list_price=5000, real_price=3000 + (i % 500),
                recorded_at=datetime(2026, 1, 1, tzinfo=timezone.utc)
                            + timedelta(days=i),
            ) for i in range(100)
        ]
        median_us = measure_median_us(
            lambda: detect_dark_patterns(4000, 5000, history),
            iterations=1000,
        )
        threshold = 500
        assert median_us < threshold
        print(f"\n  detect_dark_patterns(100): {median_us:.2f}μs")


class TestOptimizerPerformance:

    def test_optimize_complex_tree_under_1ms(self):
        """深いネストを持つ複雑ツリーの最適化"""
        # 7層のネスト AND + 重複多数
        leaves = [
            AlertCondition(op="PRICE_BELOW", value=1000),
            AlertCondition(op="FREE_SHIPPING", value=True),
            AlertCondition(op="TRUST_AT_LEAST", value=70),
        ]
        tree = AlertCondition(op="AND", children=leaves * 3)  # 重複
        for _ in range(5):
            tree = AlertCondition(op="AND", children=[tree, *leaves])

        median_us = measure_median_us(lambda: optimize(tree), iterations=500)
        threshold = 1_000
        assert median_us < threshold
        print(f"\n  optimize(deep): {median_us:.2f}μs")


class TestScorerPerformance:

    def test_score_30records_under_5ms(self):
        history = [
            PriceRecord(
                product_key="p", platform="amazon",
                list_price=6000, real_price=4000 + (i % 200),
                recorded_at=datetime(2026, 1, 1, tzinfo=timezone.utc)
                            + timedelta(days=i),
            ) for i in range(30)
        ]
        median_us = measure_median_us(
            lambda: score_buy_timing(4100, 6000, history),
            iterations=200,
        )
        threshold = 5_000
        assert median_us < threshold
        print(f"\n  score_buy_timing(30): {median_us:.0f}μs")


class TestPerformanceReport:
    """全モジュールのパフォーマンスを1テストで確認し、サマリを出力"""

    def test_print_performance_summary(self):
        print("\n\n" + "=" * 60)
        print(f"{'Function':<35} {'Median μs':>10} {'Budget μs':>10}")
        print("-" * 60)

        # Trie.suggest 100k
        t = Trie()
        for i in range(100_000):
            t.insert(f"id_{i:06d}")
        us = measure_median_us(lambda: t.suggest("id_0", limit=100), iterations=20)
        print(f"{'Trie.suggest(100k)':<35} {us:>10.0f} {'50000':>10}")

        # predict_price 1000
        records = [
            PriceRecord("p", "amazon", 1200, 1000 + (i % 500),
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i in range(1000)
        ]
        us = measure_median_us(lambda: predict_price(records), iterations=50)
        print(f"{'predict_price(1000)':<35} {us:>10.0f} {'5000':>10}")

        # simulate_customs
        us = measure_median_us(
            lambda: simulate_customs(20_000, 3_000, "衣類", 25_000),
            iterations=5000)
        print(f"{'simulate_customs':<35} {us:>10.3f} {'10':>10}")

        # calculate_tco
        us = measure_median_us(
            lambda: calculate_tco(50_000, "inkjet_printer", 5), iterations=1000)
        print(f"{'calculate_tco':<35} {us:>10.2f} {'100':>10}")

        # score_buy_timing
        history = [
            PriceRecord("p", "amazon", 6000, 4000 + (i % 200),
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i in range(30)
        ]
        us = measure_median_us(
            lambda: score_buy_timing(4100, 6000, history), iterations=200)
        print(f"{'score_buy_timing(30)':<35} {us:>10.2f} {'5000':>10}")

        print("=" * 60)
        # 通常は常に pass、閾値超過は個別テストで止まる
        assert True
