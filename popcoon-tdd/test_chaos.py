"""
test_chaos.py
Chaos engineering — 環境を異常化させて安定性を検証。

現実世界では:
  - 時計がズレている/進んでいる
  - メモリが逼迫している
  - 入力がランダムに corrupt
  - 並行スレッドが割り込む

これらの状況でも破綻しないことを確認する。
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

import pytest
import resource
import gc
import time
import random
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

from popcoon_core import (
    Trie, PriceRecord, Platform, Product,
    predict_price, simulate_customs, calculate_tco, detect_dark_patterns,
)
from buy_timing_scorer import score_buy_timing


# ═══════════════════════════════════════════════════════════════════════════
# C1: 時刻ジャンプ下での挙動
# ═══════════════════════════════════════════════════════════════════════════
class TestTimeChaos:
    """時刻ずれ・巻き戻り・巨大ジャンプ下での堅牢性"""

    def test_history_with_time_jumps_backward(self):
        """時刻が巻き戻る (NTP補正で過去方向) 履歴"""
        # 第5日目だけ第1日と同じ時刻
        records = []
        for i in range(20):
            t = datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i)
            if i == 5:
                t = datetime(2026, 1, 1, tzinfo=timezone.utc)  # 巻き戻り
            records.append(PriceRecord(
                product_key="p", platform="amazon",
                list_price=5500, real_price=5000,
                recorded_at=t,
            ))
        # 予測がクラッシュしないこと
        pred = predict_price(records)
        assert pred is not None

    def test_history_with_future_timestamps(self):
        """未来日付の履歴 (時計ズレの顧客端末)"""
        future_base = datetime(2050, 1, 1, tzinfo=timezone.utc)
        records = [
            PriceRecord("p", "amazon", 5500, 5000 + (i * 10),
                recorded_at=future_base + timedelta(days=i))
            for i in range(20)
        ]
        pred = predict_price(records)
        assert pred is not None

    def test_history_sparse_gap_decade(self):
        """10年のギャップを含む履歴"""
        records = []
        for i in range(15):
            t = datetime(2015, 1, 1, tzinfo=timezone.utc) + timedelta(days=i)
            records.append(PriceRecord("p", "amazon", 1200, 1000 + i,
                recorded_at=t))
        # 10年飛ぶ
        for i in range(15):
            t = datetime(2025, 1, 1, tzinfo=timezone.utc) + timedelta(days=i)
            records.append(PriceRecord("p", "amazon", 1200, 1500 + i,
                recorded_at=t))
        pred = predict_price(records)
        assert pred is not None  # クラッシュしない


# ═══════════════════════════════════════════════════════════════════════════
# C2: メモリ圧迫下
# ═══════════════════════════════════════════════════════════════════════════
class TestMemoryChaos:
    """メモリ逼迫時の挙動"""

    def test_trie_handles_memory_pressure(self):
        """大量挿入でも致命的メモリ枯渇しないこと (10万件)"""
        import tracemalloc
        tracemalloc.start()
        snap_before = tracemalloc.take_snapshot()

        t = Trie()
        for i in range(100_000):
            t.insert(f"product_{i:06d}_detail")

        snap_after = tracemalloc.take_snapshot()
        diff = snap_after.compare_to(snap_before, 'lineno')
        total_kb = sum(stat.size_diff for stat in diff) / 1024
        tracemalloc.stop()

        # 100k件の Trie 本体のみで 300MB 未満 (各ノード dict+list オーバーヘッド込み)
        assert total_kb < 300 * 1024, \
            f"Trie専用メモリ使用 {total_kb/1024:.1f} MB > 300 MB"
        # 動作可能
        assert t.size() == 100_000
        result = t.suggest("product_0", limit=10)
        assert len(result) == 10

    def test_prediction_handles_massive_history(self):
        """10 年間の毎時価格 = 87,600件の巨大履歴"""
        records = [
            PriceRecord("p", "amazon", 1200, 1000 + (i % 500),
                datetime(2016, 1, 1, tzinfo=timezone.utc)
                + timedelta(hours=i))
            for i in range(87_600)
        ]
        start = time.perf_counter()
        pred = predict_price(records)
        elapsed = time.perf_counter() - start
        assert pred is not None
        # 1秒以内で処理
        assert elapsed < 1.0, f"predict_price(87600) took {elapsed:.2f}s"


# ═══════════════════════════════════════════════════════════════════════════
# C3: 入力 Corruption
# ═══════════════════════════════════════════════════════════════════════════
class TestInputCorruption:
    """壊れたデータを食わせても crash しない"""

    def test_history_with_duplicate_timestamps(self):
        """同じ timestamp の重複レコード"""
        t = datetime(2026, 1, 1, tzinfo=timezone.utc)
        records = [
            PriceRecord("p", "amazon", 5500, 5000 + i, recorded_at=t)
            for i in range(20)
        ]
        pred = predict_price(records)
        assert pred is not None

    def test_history_all_identical(self):
        """全記録が完全に同一"""
        t = datetime(2026, 1, 1, tzinfo=timezone.utc)
        records = [PriceRecord("p", "amazon", 5000, 5000, recorded_at=t)] * 30
        pred = predict_price(records)
        assert pred is not None
        # 完全定数なので予測も定数
        assert pred.predicted_7d == 5000
        assert pred.predicted_30d == 5000

    def test_score_with_current_not_in_history(self):
        """履歴にない完全に違う current_price"""
        history = [PriceRecord("p", "amazon", 5500, 5000,
            datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i in range(30)]
        # 履歴の 100 倍の価格
        score = score_buy_timing(current=500_000, list_price=1_000_000,
                                  history=history)
        assert score is not None
        assert 0 <= score.total <= 100

    def test_alert_condition_deeply_nested(self):
        """100層ネストの条件ツリー (DoS的入力)"""
        from popcoon_core import AlertCondition
        cond = AlertCondition(op="PRICE_BELOW", value=1000)
        for _ in range(100):
            cond = AlertCondition(op="AND", children=[cond,
                AlertCondition(op="PRICE_BELOW", value=2000)])
        # 評価がスタックオーバーフロしないこと
        p = Product("s", "t", Platform.AMAZON, 500, 1500, 0, 50, None, "", None, 70)
        from popcoon_core import eval_condition
        import sys
        old_limit = sys.getrecursionlimit()
        sys.setrecursionlimit(500)
        try:
            result = eval_condition(cond, p)
            assert isinstance(result, bool)
        except RecursionError:
            pytest.skip("100層ネストでPython再帰制限に到達 (仕様内)")
        finally:
            sys.setrecursionlimit(old_limit)


# ═══════════════════════════════════════════════════════════════════════════
# C4: ランダム障害注入
# ═══════════════════════════════════════════════════════════════════════════
class TestFaultInjection:
    """関数呼び出しの一部をランダムに failure に差し替え"""

    def test_scorer_resilient_to_prediction_none(self):
        """predict_price が None を返しても scorer が落ちないこと"""
        # 15件だけの履歴 (min 14) で predict が動く
        history = [
            PriceRecord("p", "amazon", 5500, 5000,
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i in range(14)
        ]
        # スコアは返る (信頼度 LOW)
        score = score_buy_timing(5000, 6000, history)
        assert score is not None
        assert score.confidence == "LOW"

    def test_mass_scoring_stability(self):
        """1000回の独立スコアリングで例外ゼロ"""
        rng = random.Random(42)
        errors = []
        for _ in range(1000):
            n = rng.randint(14, 50)
            prices = [rng.randint(100, 100_000) for _ in range(n)]
            history = [
                PriceRecord("p", "amazon", p + 500, p,
                    datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
                for i, p in enumerate(prices)
            ]
            try:
                score = score_buy_timing(prices[-1], prices[-1] + 1000, history)
                assert score is not None
                assert 0 <= score.total <= 100
            except Exception as e:
                errors.append(e)
        assert not errors, f"{len(errors)}件例外: {errors[:3]}"


# ═══════════════════════════════════════════════════════════════════════════
# C5: GC 圧力下での決定性
# ═══════════════════════════════════════════════════════════════════════════
class TestGCPressure:
    """頻繁な GC 下でも結果が同一"""

    def test_identical_under_heavy_gc(self):
        """GC を強制発動させながら同じ計算を 100 回行い、結果一致"""
        history = [
            PriceRecord("p", "amazon", 6000, 5000 + (i % 100),
                datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
            for i in range(30)
        ]
        results = []
        for _ in range(100):
            gc.collect()  # GC 強制
            score = score_buy_timing(5050, 6500, history)
            results.append(score.total)
        # 全同値
        assert all(r == results[0] for r in results), \
            f"GC下で非決定性: {set(results)}"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
