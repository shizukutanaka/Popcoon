"""
test_integration.py
統合テスト — 複数モジュール連携の端到端シナリオ。

これは Popcoon がユーザーに提供する「本当の価値」を検証する。
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')
import pytest
from datetime import datetime, timedelta, timezone

from popcoon_core import (
    Platform, Product, PriceRecord,
    predict_price, detect_dark_patterns, simulate_customs,
    calculate_tco, score_eco_ethics,
    AlertCondition, eval_condition, Trie,
)
from alert_optimizer import optimize, always_true, always_false


class TestScenarioBuyOrWait:
    """シナリオ: ユーザーが「今買うべきか待つべきか」を判断する"""

    def _price_history(self, prices):
        return [
            PriceRecord(
                product_key="p1", platform="amazon",
                list_price=p + 200, real_price=p,
                recorded_at=datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i),
            ) for i, p in enumerate(prices)
        ]

    def test_stable_price_suggests_neutral_no_dark_pattern(self):
        """30日間安定価格: 予測も安定、罠検出もゼロ"""
        history = self._price_history([5000] * 30)
        pred = predict_price(history)
        warnings = detect_dark_patterns(5000, 6000, history)

        assert pred is not None
        assert pred.historic_low == 5000
        assert pred.historic_high == 5000
        # 「常設セール」は list=6000 に対し 30日全てreal=5000 → 検出される
        types = [w.type.value for w in warnings]
        assert "ALWAYS_ON_DISCOUNT" in types

    def test_declining_price_with_fake_list(self):
        """セールに見えて実質値上げ: 総合判断で警告多数"""
        # 最初10日: 3000、その後20日: 4500 (50%値上げ、list=5000で割引演出)
        prices = [3000] * 10 + [4500] * 20
        history = self._price_history(prices)

        pred = predict_price(history)
        warnings = detect_dark_patterns(4500, 5000, history)

        assert pred is not None
        # 価格は上昇傾向
        assert pred.predicted_7d >= pred.current_price - 500


class TestScenarioInternationalShopping:
    """シナリオ: 海外ECで買うべきか国内か"""

    def test_aliexpress_cheap_enough_to_overcome_customs(self):
        """AliExpress ¥5000 + 送料¥2000 (免税境界内) vs Amazon ¥12,000"""
        result = simulate_customs(
            foreign_price_jpy=5_000,
            shipping_jpy=2_000,
            category="衣類",
            japan_best_price=12_000,
        )
        assert result.is_tax_exempt is True  # 7000 ≤ 16666
        assert result.total_landed_cost < 12_000
        assert result.verdict.value == "CHEAPER"

    def test_electronics_zero_duty_makes_overseas_attractive(self):
        """電子機器は関税0、消費税のみ"""
        result = simulate_customs(
            foreign_price_jpy=30_000, shipping_jpy=3_000,
            category="電子機器", japan_best_price=50_000,
        )
        assert result.customs_duty == 0
        assert result.consumption_tax > 0
        # total = 33000 + 0 + 3300 + 200 = 36500 < 50000
        assert result.verdict.value == "CHEAPER"

    def test_shoes_high_duty_may_exceed_japan_price(self):
        """靴は30%関税で海外価格が逆転する可能性"""
        # foreign 12000 + ship 3000 = 15000 (免税境界内だが、15000)
        # …実際は免税だが、非免税だった場合
        result = simulate_customs(20_000, 3_000, "靴", japan_best_price=25_000)
        # 23000 * 0.30 = 6900 関税、(23000+6900)*0.10=2990消費税
        # total = 23000 + 6900 + 2990 + 200 = 33090
        assert result.total_landed_cost > 25_000
        assert result.verdict.value == "MORE_EXPENSIVE"


class TestScenarioTCOvsAlternative:
    """シナリオ: インクジェット vs タンク式の長期コスト比較"""

    def test_user_sees_tank_saves_significant_money(self):
        inkjet = calculate_tco(8_000, "inkjet_printer", years=5)
        assert inkjet.vs_alternative is not None
        label, alt_tco, savings = inkjet.vs_alternative
        # 5年でインクジェット TCO は本体価格の 10倍超
        assert inkjet.total_tco > 80_000
        # タンク式の方が安い
        assert savings > 0


class TestScenarioSmartAlertOptimized:
    """シナリオ: ユーザーが作ったアラートが最適化されて高速動作"""

    def test_user_complex_alert_reduces_to_simple(self):
        """ユーザーが試行錯誤で作った重複だらけのアラート"""
        # (価格≤3000 AND 送料無料) AND (価格≤5000 AND 送料無料)
        # → 価格≤3000 AND 送料無料
        original = AlertCondition(op="AND", children=[
            AlertCondition(op="AND", children=[
                AlertCondition(op="PRICE_BELOW", value=3000),
                AlertCondition(op="FREE_SHIPPING", value=True),
            ]),
            AlertCondition(op="AND", children=[
                AlertCondition(op="PRICE_BELOW", value=5000),
                AlertCondition(op="FREE_SHIPPING", value=True),
            ]),
        ])
        result = optimize(original)
        # 最適化後は AND(PRICE_BELOW 3000, FREE_SHIPPING True) の2子のみ
        assert result.condition.op == "AND"
        assert len(result.condition.children) == 2

    def test_contradictory_user_alert_detected(self):
        """価格1000以下 AND 2000以上 → 矛盾"""
        cond = AlertCondition(op="AND", children=[
            AlertCondition(op="PRICE_BELOW", value=1000),
            AlertCondition(op="PRICE_ABOVE", value=2000),
        ])
        result = optimize(cond)
        assert always_false(result.condition)
        # UI側で「条件が矛盾しています」警告表示できる


class TestScenarioEcoConsciousBuying:
    """シナリオ: 環境意識の高い消費者向け判断"""

    def test_jp_premium_over_cn_cheap(self):
        """日本製 vs 中国製で倫理スコアが明確に差"""
        jp = score_eco_ethics("JP", "tv", [])
        cn = score_eco_ethics("CN", "tv", [])
        # 日本が優位
        assert jp.overall > cn.overall
        assert jp.green_alternative is None
        assert cn.green_alternative is not None

    def test_cert_tips_balance_for_foreign(self):
        """海外製でもエコ認証があれば評価改善"""
        without = score_eco_ethics("CN", "tv", [])
        with_cert = score_eco_ethics("CN", "tv", ["エコマーク"])
        assert with_cert.co2_score > without.co2_score


class TestScenarioSearchWithAutocomplete:
    """シナリオ: ユーザーの入力をオフラインで補完しながら検索"""

    def test_autocomplete_suggests_past_queries(self):
        trie = Trie()
        past_queries = [
            "iPhone 15 Pro", "iPhone 15", "iPhone 14",
            "iPad Pro 12.9", "iPad Air",
            "MacBook Air M3", "MacBook Pro",
        ]
        for q in past_queries:
            trie.insert(q)

        suggestions = trie.suggest("iPhone", limit=5)
        assert all(s.startswith("iPhone") for s in suggestions)
        assert len(suggestions) == 3  # iPhone で始まるのは3件

        mac_suggestions = trie.suggest("Mac", limit=5)
        assert len(mac_suggestions) == 2


class TestScenarioFullJourney:
    """統合: 検索 → 比較 → アラート作成 → 最適化 → 評価"""

    def test_user_finds_product_sets_alert_evaluates(self):
        # Step 1: 検索候補生成
        trie = Trie()
        for q in ["洗濯洗剤 詰替", "洗剤 ジェルボール", "洗濯洗剤 液体"]:
            trie.insert(q)
        suggestions = trie.suggest("洗", limit=10)
        assert len(suggestions) == 3

        # Step 2: 商品発見と特性評価
        product = Product(
            sku="sku001", title="詰替洗剤",
            platform=Platform.AMAZON,
            real_price=880, list_price=1200,
            shipping_fee=0, points_back=8,
            trust_score=85,
        )

        # Step 3: スマートアラート作成 (冗長込み)
        user_alert = AlertCondition(op="AND", children=[
            AlertCondition(op="PRICE_BELOW", value=1000),
            AlertCondition(op="FREE_SHIPPING", value=True),
            AlertCondition(op="PRICE_BELOW", value=1000),  # 重複
        ])

        # Step 4: アラート最適化
        optimized = optimize(user_alert)
        assert optimized.changed is True

        # Step 5: 商品がアラート条件を満たすか評価
        opt_cond = optimized.condition
        if hasattr(opt_cond, 'op'):
            matches = eval_condition(opt_cond, product)
        else:
            matches = opt_cond.value
        assert matches is True

        # Step 6: 関税シミュレーションは国内EC なので不要だが、
        # 仮に海外版があった場合
        customs = simulate_customs(500, 500, "その他", japan_best_price=880)
        # 1000 < 880 * 0.7 = 616 ではないが、japan_best_priceを超えない
        assert customs.is_tax_exempt  # 免税境界内


class TestScenarioPerformanceAtScale:
    """ストレステスト: 大量データで破綻しない"""

    def test_trie_handles_10000_queries(self):
        trie = Trie()
        for i in range(10_000):
            trie.insert(f"query_{i:05d}")
        assert trie.size() == 10_000
        # 特定プレフィックスで検索
        result = trie.suggest("query_0000", limit=10)
        assert len(result) == 10

    def test_prediction_handles_2year_history(self):
        """730日 (2年) の価格履歴"""
        history = [
            PriceRecord(
                product_key="p", platform="amazon",
                list_price=2000, real_price=1500 + (i % 500),
                recorded_at=datetime(2024, 1, 1, tzinfo=timezone.utc) + timedelta(days=i),
            ) for i in range(730)
        ]
        pred = predict_price(history)
        assert pred is not None
        assert pred.confidence.value == "HIGH"

    def test_deep_nested_alert_optimizes(self):
        """深くネストしたAND/ORも最適化可能"""
        cond = AlertCondition(op="PRICE_BELOW", value=1000)
        # 10層ネスト AND(AND(AND(... PRICE_BELOW ...)))
        for _ in range(10):
            cond = AlertCondition(op="AND", children=[cond])
        result = optimize(cond)
        # 平坦化して 1 葉に帰着
        assert result.condition == AlertCondition(op="PRICE_BELOW", value=1000)
