"""
test_golden_snapshots.py
ゴールデン/スナップショットテスト — 固定入力に対する出力を固定化する。

これは「振る舞いの意図しない変化」を検出する最終防衛ライン。
- リファクタで内部を大きく変えても、I/Oは変わらないことを保証
- 新機能追加で既存シナリオが壊れないことを保証

ハッシュベースで柔軟: 実行環境が変わっても確定的な入力に対して同じ結果。
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

import hashlib
import json
from dataclasses import asdict, is_dataclass
from datetime import datetime, timedelta, timezone
from enum import Enum

import pytest

from popcoon_core import (
    PriceRecord, Platform, Product,
    predict_price, simulate_customs, calculate_tco,
    detect_dark_patterns, score_eco_ethics,
    AlertCondition,
)
from alert_optimizer import optimize
from buy_timing_scorer import score_buy_timing


# ── スナップショット化ヘルパー ──────────────────────────────────────────────
def canonicalize(obj):
    """任意のオブジェクトを JSON シリアル可能な形式に正規化"""
    if isinstance(obj, Enum):
        return f"<{obj.__class__.__name__}.{obj.name}>"
    if isinstance(obj, datetime):
        return obj.isoformat()
    if is_dataclass(obj) and not isinstance(obj, type):
        return {k: canonicalize(v) for k, v in asdict(obj).items()}
    if isinstance(obj, (list, tuple)):
        return [canonicalize(x) for x in obj]
    if isinstance(obj, dict):
        return {k: canonicalize(v) for k, v in sorted(obj.items())}
    return obj


def snapshot_hash(obj) -> str:
    """決定論的ハッシュ — 小さな変更でも捕捉"""
    canon = canonicalize(obj)
    text = json.dumps(canon, sort_keys=True, ensure_ascii=False, default=str)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def _fixed_history(prices):
    """固定日付の履歴"""
    return [
        PriceRecord(
            product_key="snapshot_product",
            platform="amazon",
            list_price=p + 500,
            real_price=p,
            recorded_at=datetime(2026, 1, 1, tzinfo=timezone.utc)
                        + timedelta(days=i),
        ) for i, p in enumerate(prices)
    ]


# ═══════════════════════════════════════════════════════════════════════════
# 価格予測のスナップショット
# ═══════════════════════════════════════════════════════════════════════════
class TestPricePredictionSnapshots:

    def test_flat_price_snapshot(self):
        """30日間一定価格の予測結果を固定化"""
        history = _fixed_history([5000] * 30)
        pred = predict_price(history)
        snapshot = {
            "current_price": pred.current_price,
            "predicted_7d": pred.predicted_7d,
            "predicted_30d": pred.predicted_30d,
            "historic_low": pred.historic_low,
            "historic_high": pred.historic_high,
            "confidence": pred.confidence.value,
        }
        # スナップショット: 不変の期待値
        expected = {
            "current_price": 5000,
            "predicted_7d": 5000,
            "predicted_30d": 5000,
            "historic_low": 5000,
            "historic_high": 5000,
            "confidence": "MEDIUM",
        }
        assert snapshot == expected, \
            f"予測結果が変化: {snapshot} vs expected {expected}"

    def test_declining_price_snapshot(self):
        """下降トレンドの予測ハッシュ固定"""
        history = _fixed_history([10000 - i * 100 for i in range(30)])
        pred = predict_price(history)
        current_hash = snapshot_hash({
            "current": pred.current_price,
            "p7": pred.predicted_7d,
            "p30": pred.predicted_30d,
            "conf": pred.confidence.value,
        })
        # 固定ハッシュ (初回実装時の値)
        expected_hash = "ac6fe61e7fb83f8b"
        # 実行して値を確認
        if current_hash != expected_hash:
            pytest.fail(
                f"予測スナップショット変化: {current_hash} != {expected_hash}\n"
                f"  current={pred.current_price}, p7={pred.predicted_7d}, "
                f"p30={pred.predicted_30d}, conf={pred.confidence.value}\n"
                f"  → 意図的変更ならこのhashに更新"
            )


# ═══════════════════════════════════════════════════════════════════════════
# 関税シミュレーターのスナップショット
# ═══════════════════════════════════════════════════════════════════════════
class TestCustomsSnapshots:

    @pytest.mark.parametrize("case,expected", [
        # (foreign, ship, category, japan_best, expected_total, expected_is_exempt)
        ((10_000, 5_000, "衣類", None),        (15_000, True)),
        ((20_000, 5_000, "衣類", None),        (31_000, False)),
        ((30_000, 3_000, "電子機器", 50_000), (36_500, False)),
        ((20_000, 5_000, "靴", None),          (35_950, False)),
        ((50_000, 10_000, "化粧品", None),     (68_180, False)),
    ])
    def test_customs_fixed_outputs(self, case, expected):
        foreign, ship, category, japan = case
        expected_total, expected_exempt = expected
        result = simulate_customs(foreign, ship, category, japan)
        assert result.total_landed_cost == expected_total, \
            f"入力 {case}: 着払い {result.total_landed_cost} != 期待 {expected_total}"
        assert result.is_tax_exempt == expected_exempt


# ═══════════════════════════════════════════════════════════════════════════
# TCO計算のスナップショット
# ═══════════════════════════════════════════════════════════════════════════
class TestTCOSnapshots:

    def test_inkjet_5yr_reference(self):
        """インクジェットプリンタ ¥8,000/5年 の標準シナリオ"""
        result = calculate_tco(8_000, "inkjet_printer", 5, intensity=1.0)
        snapshot = {
            "purchase": result.purchase_price,
            "consumables_5yr": result.consumables_total,
            "energy_5yr": result.energy_total,
            "maintenance": result.maintenance,
            "residual": result.residual_value,
            "total": result.total_tco,
            "monthly": result.tco_per_month,
        }
        expected = {
            "purchase": 8_000,
            "consumables_5yr": 106_000,       # 実測値
            "energy_5yr": 365,                # 15W*0.5h*365*5/1000*27 = 365.06
            "maintenance": 800,               # 8000//10
            "residual": 0,                    # 5*0.01 超過で 0
            "total": 115_165,
            "monthly": 1_919,
        }
        assert snapshot == expected, f"TCO変化: {snapshot} vs {expected}"


# ═══════════════════════════════════════════════════════════════════════════
# ダークパターン検出のスナップショット
# ═══════════════════════════════════════════════════════════════════════════
class TestDarkPatternSnapshots:

    def test_fixed_scenario_always_on_discount(self):
        """30日常設セール → ALWAYS_ON_DISCOUNT + CHARM_PRICING"""
        history = _fixed_history([3000] * 30)
        warnings = detect_dark_patterns(current_price=2980, list_price=5000,
                                        history=history)
        types = sorted([w.type.value for w in warnings])
        expected_types = ["ALWAYS_ON_DISCOUNT", "CHARM_PRICING", "INFLATED_LIST_PRICE"]
        assert types == sorted(expected_types), \
            f"検出罠の組合せ変化: {types}"


# ═══════════════════════════════════════════════════════════════════════════
# CO2 / 倫理スコアのスナップショット
# ═══════════════════════════════════════════════════════════════════════════
class TestEcoEthicsSnapshots:

    def test_japan_tv_baseline(self):
        score = score_eco_ethics("JP", "tv", [])
        assert score.overall == 62
        assert score.co2_score == 45
        assert score.labor_score == 82
        assert score.green_alternative is None

    def test_china_tv_with_eco_cert(self):
        score = score_eco_ethics("CN", "tv", ["エコマーク"])
        # エコ認証で CO2スコアは基本25点に+10で35点
        assert score.co2_score == 35
        assert score.labor_score == 52
        assert score.green_alternative is not None


# ═══════════════════════════════════════════════════════════════════════════
# AlertOptimizer のスナップショット
# ═══════════════════════════════════════════════════════════════════════════
class TestAlertOptimizerSnapshots:

    def test_complex_alert_optimization(self):
        """典型的なユーザーアラートが期待通り最適化される"""
        original = AlertCondition(op="AND", children=[
            AlertCondition(op="AND", children=[
                AlertCondition(op="PRICE_BELOW", value=3000),
                AlertCondition(op="FREE_SHIPPING", value=True),
            ]),
            AlertCondition(op="AND", children=[
                AlertCondition(op="PRICE_BELOW", value=5000),  # より緩い
                AlertCondition(op="FREE_SHIPPING", value=True),  # 重複
            ]),
        ])
        result = optimize(original)
        # 期待: AND(PRICE_BELOW 3000, FREE_SHIPPING True) の2子
        assert result.condition.op == "AND"
        assert len(result.condition.children) == 2
        assert result.changed is True

        # 子の構造を正規化してハッシュ比較
        structure = canonicalize(result.condition)
        current = snapshot_hash(structure)
        # 実測値に固定
        expected_hash = "37d1c41f4e655346"
        assert current == expected_hash, \
            f"optimizer出力ハッシュ変化: {current} != {expected_hash}"


# ═══════════════════════════════════════════════════════════════════════════
# BuyTimingScorer の統合スナップショット
# ═══════════════════════════════════════════════════════════════════════════
class TestBuyTimingScorerSnapshots:

    def test_flat_stable_scenario(self):
        """30日一定 + 僅差割引の基本シナリオ"""
        history = _fixed_history([5000] * 30)
        score = score_buy_timing(current=5000, list_price=6000, history=history)
        # 固定signal構成
        signal_names = sorted(s.name for s in score.signals)
        expected_names = sorted([
            "中立スコア",
            "価格安定 (ATL近接判定不可)",  # ATL判定不可
            "価格横ばい",
            "定価比16%OFF",
            "極めて安定",
            "十分な履歴",
            "ダークパターン検出 (常設セール)",
        ])
        assert signal_names == expected_names

    def test_atl_with_upward_trend_scenario(self):
        """ATL + 上昇予測 = 最高スコア相当"""
        history = _fixed_history([5000] * 89 + [2500])
        score = score_buy_timing(current=2500, list_price=8000, history=history)
        # verdict は BUY_NOW
        assert score.verdict.value == "BUY_NOW"
        # total は 80以上
        assert score.total >= 80


# ═══════════════════════════════════════════════════════════════════════════
# 統合スナップショット — 完全ジャーニー
# ═══════════════════════════════════════════════════════════════════════════
class TestFullJourneySnapshot:

    def test_canonical_user_scenario(self):
        """Popcoon ユーザー典型シナリオのE2E固定化"""
        # 価格履歴: セール前値上げの罠
        history = _fixed_history([3500] * 10 + [4400] * 20)

        # 1. 予測
        pred = predict_price(history)
        assert pred is not None
        assert pred.confidence.value == "MEDIUM"

        # 2. ダークパターン
        warnings = detect_dark_patterns(4400, 6000, history)
        # セール前値上げは検出されるべき
        types = [w.type.value for w in warnings]
        # 価格4400 なので 下二桁00 = CHARM ではない、が値上げ後割引
        # 常設セール or PRE_SALE_MARKUP のいずれか
        assert len(types) >= 1

        # 3. 買い時スコア
        score = score_buy_timing(4400, 6000, history)
        # 現在は高値圏 + 罠あり → 低スコア verdict NEUTRAL or WAIT
        assert score.verdict.value in ("WAIT", "NEUTRAL")

        # 4. 関税シミュレーター (海外で同等品)
        customs = simulate_customs(3_000, 500, "電子機器", japan_best_price=4_400)
        # 3500 (免税) vs 4400 → 安い
        assert customs.is_tax_exempt is True
        assert customs.total_landed_cost == 3_500

        # 5. エコスコア
        eco = score_eco_ethics("CN", "tv", [])
        # 中国産 → 代替提示
        assert eco.green_alternative is not None


# ═══════════════════════════════════════════════════════════════════════════
# スナップショット更新ヘルパー
# ═══════════════════════════════════════════════════════════════════════════
class TestSnapshotUpdateHelper:
    """スナップショット値更新用。実行時の値を出力する"""

    @pytest.mark.skip(reason="参考用、実行しない。手動で値を更新するヘルパー")
    def test_print_all_current_hashes(self):
        # 実行して出力を test_*_snapshot の expected に転記
        history_decl = _fixed_history([10000 - i * 100 for i in range(30)])
        pred = predict_price(history_decl)
        h = snapshot_hash({
            "current": pred.current_price,
            "p7": pred.predicted_7d,
            "p30": pred.predicted_30d,
            "conf": pred.confidence.value,
        })
        print(f"declining_price_hash: {h}")
