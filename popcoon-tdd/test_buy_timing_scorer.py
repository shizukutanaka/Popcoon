"""
test_buy_timing_scorer.py
買い時スコア統合エンジン — 既存3機能 (予測/罠検出/履歴) を単一スコアに集約。

ユーザーが1数値で「今買うべきか」判断できる。
既存機能の組み合わせなので、TDDで各統合ルールを検証する。
"""
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

import pytest
from datetime import datetime, timedelta, timezone
from popcoon_core import PriceRecord

from buy_timing_scorer import (
    score_buy_timing,
    BuyTimingScore,
    TimingVerdict,
    TimingSignal,
)


def _history(prices, start_day=0):
    return [
        PriceRecord(
            product_key="p1", platform="amazon",
            list_price=p + 500, real_price=p,
            recorded_at=datetime(2026, 1, 1, tzinfo=timezone.utc)
                        + timedelta(days=start_day + i),
        ) for i, p in enumerate(prices)
    ]


class TestBuyTimingScorerBasic:
    def test_insufficient_history_returns_none(self):
        """データ不足時は None を返す"""
        assert score_buy_timing(current=1000, list_price=1500, history=[]) is None

    def test_score_range_0_to_100(self):
        """全スコアは 0..100 の範囲"""
        history = _history([5000] * 30)
        score = score_buy_timing(current=5000, list_price=6000, history=history)
        assert 0 <= score.total <= 100

    def test_score_has_verdict(self):
        history = _history([5000] * 30)
        score = score_buy_timing(current=5000, list_price=6000, history=history)
        assert score.verdict in TimingVerdict


class TestBuyTimingScorerVerdict:
    """価格状況による verdict の判定"""

    def test_current_equals_all_time_low_scores_very_high(self):
        # 過去30日: 5000〜7000、現在: 4500 (ATL)
        history = _history([5000 + i * 50 for i in range(30)])
        score = score_buy_timing(current=4500, list_price=7500, history=history)
        assert score.verdict == TimingVerdict.BUY_NOW
        assert score.total >= 75

    def test_current_at_historic_high_scores_low(self):
        # 過去: 3000〜4000、現在: 4500 (新高値)
        history = _history([3000 + i * 30 for i in range(30)])
        score = score_buy_timing(current=4500, list_price=5000, history=history)
        assert score.verdict in (TimingVerdict.WAIT, TimingVerdict.NEUTRAL)
        assert score.total <= 50

    def test_stable_price_neutral(self):
        # 30日間全て5000円
        history = _history([5000] * 30)
        score = score_buy_timing(current=5000, list_price=5500, history=history)
        assert score.verdict in (TimingVerdict.NEUTRAL, TimingVerdict.BUY_NOW)


class TestBuyTimingScorerSignals:
    """スコア算出の根拠シグナルが出力される"""

    def test_signals_include_trend(self):
        history = _history([6000 - i * 50 for i in range(30)])  # 下降トレンド
        score = score_buy_timing(current=4550, list_price=6000, history=history)
        signal_names = [s.name for s in score.signals]
        assert "価格下降中" in " ".join(signal_names) or \
               any("trend" in s.name.lower() or "下降" in s.name for s in score.signals)

    def test_signals_include_dark_pattern_penalty(self):
        """セール前値上げが検出されるとスコアに反映される"""
        # 前半10日: 4000、後半20日: 5000 (25%値上げ)
        history = _history([4000] * 10 + [5000] * 20)
        score = score_buy_timing(current=4500, list_price=6000, history=history)
        # 心理罠検出で減点されている
        names = [s.name for s in score.signals]
        assert any("値上げ" in s.name or "罠" in s.name or "ダーク" in s.name
                   for s in score.signals)

    def test_signals_always_explain_score(self):
        """全てのスコアには説明シグナルが少なくとも1件ある"""
        history = _history([5000] * 30)
        score = score_buy_timing(current=5000, list_price=6000, history=history)
        assert len(score.signals) >= 1


class TestBuyTimingScorerWeighting:
    """スコア構成の重み付け"""

    def test_all_time_low_weighted_highest(self):
        """ATLは最重要シグナル"""
        # 履歴: 5000〜6000、現在 4000 (完全ATL)
        history = _history([5000 + i * 30 for i in range(30)])
        score = score_buy_timing(current=4000, list_price=7000, history=history)
        # ATL で 30点以上寄与しているはず
        atl_signal = next((s for s in score.signals if "最安" in s.name), None)
        assert atl_signal is not None
        assert atl_signal.contribution >= 25

    def test_dark_pattern_penalty_reduces_score(self):
        """同じ価格でも罠があればスコアが下がる"""
        # 罠なし: 同価格を30日維持 (list=current で割引表示なし)
        history_clean = _history([5000] * 30)
        # 罠あり: セール前値上げ + 割引演出
        history_dirty = _history([4000] * 10 + [5000] * 20)

        clean = score_buy_timing(current=4800, list_price=5000, history=history_clean)
        dirty = score_buy_timing(current=4800, list_price=6000, history=history_dirty)

        # 同じcurrent価格でも罠があれば総合スコアが下がる
        assert dirty.total < clean.total or dirty.verdict != TimingVerdict.BUY_NOW


class TestBuyTimingScorerEdgeCases:
    def test_zero_price(self):
        """価格0でも例外を出さない"""
        history = _history([0] * 30)
        score = score_buy_timing(current=0, list_price=0, history=history)
        assert score is not None
        assert 0 <= score.total <= 100

    def test_negative_price_safe(self):
        """負の価格でも例外を出さない (入力バリデーションの責務外)"""
        history = _history([-100] * 30)
        score = score_buy_timing(current=-50, list_price=100, history=history)
        assert score is None or 0 <= score.total <= 100

    def test_very_short_history_returns_low_confidence(self):
        """14件ちょうどの境界"""
        history = _history([5000] * 14)
        score = score_buy_timing(current=5000, list_price=6000, history=history)
        # 14件未満は None、14件以上は低信頼度でも score は返す
        assert score is not None
        assert score.confidence == "LOW"


class TestBuyTimingScorerDeterministic:
    """同じ入力 → 常に同じ出力"""

    def test_identical_input_produces_identical_output(self):
        history = _history([5000 + (i % 7) * 100 for i in range(30)])
        s1 = score_buy_timing(current=4800, list_price=6000, history=history)
        s2 = score_buy_timing(current=4800, list_price=6000, history=history)
        assert s1.total == s2.total
        assert s1.verdict == s2.verdict

    def test_history_order_matters(self):
        """履歴順序が結果に影響する (時系列なので当然)"""
        ascending = _history([1000 + i * 100 for i in range(30)])
        descending = _history([3900 - i * 100 for i in range(30)])
        s_asc = score_buy_timing(current=3900, list_price=5000, history=ascending)
        s_desc = score_buy_timing(current=1000, list_price=5000, history=descending)
        # 下降後の最低値 vs 上昇後の最高値 → スコアが大きく異なる
        assert s_asc.total != s_desc.total


class TestBuyTimingScorerMutationCoverage:
    """Mutationで露出した弱点を塞ぐピンポイントテスト群"""

    def test_min_history_boundary_14_exactly(self):
        """BTS01: 14件ぴったりで score 返る、13件は None"""
        history_14 = _history([5000] * 14)
        history_13 = _history([5000] * 13)
        assert score_buy_timing(5000, 6000, history_14) is not None
        assert score_buy_timing(5000, 6000, history_13) is None

    def test_verdict_buy_now_at_exactly_70(self):
        """BTS06: total=70 で BUY_NOW (70が境界)"""
        # 完全ATL + 十分な履歴 + 定価比割引 で 70+ を作る
        history = _history([7000 - i * 50 for i in range(95)])  # 下降
        score = score_buy_timing(current=2250, list_price=7000, history=history)
        assert score.total >= 70
        assert score.verdict == TimingVerdict.BUY_NOW

    def test_verdict_wait_at_or_below_35(self):
        """BTS07: total≤35 で WAIT"""
        # 過去最高値 + 上昇トレンド + 割引無し + ダークパターン
        history = _history([3000 + i * 50 for i in range(30)])
        score = score_buy_timing(current=4500, list_price=4500, history=history)
        # このケースは中立付近だが、別パターンで検証
        # 新高値 + 上昇中 + 罠で 35以下に
        history_bad = _history([3000] * 10 + [4400] * 20)  # セール前値上げ
        score_bad = score_buy_timing(current=4400, list_price=5000, history=history_bad)
        # 低スコアであるべき
        if score_bad.total <= 35:
            assert score_bad.verdict == TimingVerdict.WAIT
        # 仕様固定: 35以下なら必ず WAIT
        for s in [score, score_bad]:
            if s.total <= 35:
                assert s.verdict == TimingVerdict.WAIT

    def test_verdict_wait_boundary_exactly_35(self):
        """BTS07 追加: total=35 ちょうどで WAIT、total=36 で NEUTRAL"""
        # 低スコアを意図的に構築し 35 ちょうどを作る
        # これは実装値に依存するので、「35未満なら必ずWAIT」を強く固定
        history = _history([5000] * 30)  # 安定
        # 高値 + 上昇 + 罠 → total 低く
        # 人工的に BuyTimingScore を作って verdict 判定関数を検証
        from buy_timing_scorer import _decide_verdict
        assert _decide_verdict(35) == TimingVerdict.WAIT
        assert _decide_verdict(34) == TimingVerdict.WAIT
        assert _decide_verdict(36) == TimingVerdict.NEUTRAL
        # これにより BTS07 (閾値20) を検出可能

    def test_score_never_exceeds_100(self):
        """BTS08: 極端ポジティブ入力でも 100 に固定"""
        # 最も好条件を作る: ATL + 下降 + 大幅割引 + 安定 + 豊富履歴
        history = _history([10000 - i * 50 for i in range(150)])
        score = score_buy_timing(current=3000, list_price=15000, history=history)
        assert 0 <= score.total <= 100
        # 具体的にクリップされた証拠
        raw_sum = sum(s.contribution for s in score.signals)
        # raw_sum は 100 を超えていれば、normalization signalが入っているはず
        if raw_sum > 100:
            norm_signals = [s for s in score.signals if s.name == "スコア正規化"]
            assert len(norm_signals) >= 1

    def test_score_upper_clip_enforced_on_extreme_positive(self):
        """BTS08: ATL+上昇+割引+安定+豊富+ダークパ無 で >100 になる入力を意図構築"""
        # 履歴80日一定 + 15日急落 → 現在 ATL、List=当時定価で 30%OFF
        # list_price=5100, 80日 real=5100 (割引なし), 15日 real=3500
        # ⇒ 常設セール判定: 15/95=16% ≪ 90% → 検出されない
        # ⇒ 参考価格: history_max=5100、list=5100 ≪ 5100*1.5 → 検出されない
        from popcoon_core import PriceRecord
        hist = [PriceRecord("p", "amazon", list_price=5100, real_price=5100,
                recorded_at=datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=i))
                for i in range(80)]
        hist += [PriceRecord("p", "amazon", list_price=5100, real_price=3500,
                 recorded_at=datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=80 + i))
                 for i in range(15)]
        score = score_buy_timing(current=3500, list_price=5100, history=hist)
        assert score is not None
        # raw signal sum may exceed 100; total must be capped
        raw = sum(s.contribution for s in score.signals if s.name != "スコア正規化")
        # どのような raw_sum でも total は 100 以下
        assert score.total <= 100, f"total={score.total} must be <= 100"
        # クリップが発動した証拠 (raw > 100 の場合のみ)
        if raw > 100:
            clip = next((s for s in score.signals if s.name == "スコア正規化"), None)
            assert clip is not None, f"raw={raw} > 100 なら正規化signal必須"
            assert clip.contribution < 0

    def test_stable_price_rewards_positively(self):
        """BTS9: 極めて安定な価格は +10 点寄与 (ボラティリティsignal)"""
        history = _history([5000] * 30)  # 完全に安定
        score = score_buy_timing(current=5000, list_price=6000, history=history)
        # ボラティリティ由来の "極めて安定" signal のみ検証 (ATL由来と区別)
        vol_sig = next((s for s in score.signals if s.name == "極めて安定"), None)
        assert vol_sig is not None, f"極めて安定signalが必要: {[s.name for s in score.signals]}"
        assert vol_sig.contribution > 0, \
            f"安定は加点のはずが {vol_sig.contribution}"

    def test_declining_trend_lowers_score(self):
        """BTS10: 下降トレンドは買い時スコアを下げる (待ちが有利)"""
        history_declining = _history([6000 - i * 60 for i in range(30)])
        history_stable = _history([5000] * 30)
        score_decl = score_buy_timing(current=4230, list_price=6000, history=history_declining)
        score_stab = score_buy_timing(current=5000, list_price=6000, history=history_stable)
        # 下降中は「待ちが有利」のため score は下がるべき
        decl_trend = [s for s in score_decl.signals if "下降" in s.name]
        assert decl_trend, "下降signalが含まれるはず"
        assert decl_trend[0].contribution < 0, \
            f"下降トレンド signal は負のはずが {decl_trend[0].contribution}"

    def test_rising_trend_raises_score(self):
        """BTS10補強: 上昇は買い時スコアを上げる (今が最安)"""
        history_rising = _history([3000 + i * 60 for i in range(30)])
        score = score_buy_timing(current=4740, list_price=5500, history=history_rising)
        rise_trend = [s for s in score.signals
                      if ("上昇" in s.name or "微上昇" in s.name)]
        if rise_trend:
            assert rise_trend[0].contribution > 0, \
                f"上昇は加点のはずが {rise_trend[0].contribution}"


class TestBuyTimingScorerProperty:
    """プロパティベーステスト"""

    from hypothesis import given, strategies as st, settings, HealthCheck

    @given(st.lists(st.integers(min_value=100, max_value=100_000),
                    min_size=14, max_size=100))
    @settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow,
                                                       HealthCheck.differing_executors])
    def test_property_score_always_in_range(self, prices):
        """任意の履歴で score は 0..100"""
        history = _history(prices)
        current = prices[-1]
        score = score_buy_timing(current=current, list_price=current * 2, history=history)
        if score is not None:
            assert 0 <= score.total <= 100

    @given(st.lists(st.integers(min_value=100, max_value=10_000),
                    min_size=14, max_size=60))
    @settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow,
                                                       HealthCheck.differing_executors])
    def test_property_contributions_sum_reasonable(self, prices):
        """各signalのcontribution合計 ≈ total"""
        history = _history(prices)
        current = prices[-1]
        score = score_buy_timing(current=current, list_price=current + 1000, history=history)
        if score is not None and score.signals:
            sum_contrib = sum(s.contribution for s in score.signals)
            # 合計は total の ±10% 以内
            assert abs(sum_contrib - score.total) <= 10, \
                f"total={score.total}, sum={sum_contrib}"


class TestZeroPricePoisoning:
    """価格履歴に混入した ¥0 レコードが判定を壊さないこと (2026-08 回帰ガード)。

    FallbackScraper は price が取れないとき realPrice=0 の Product を捏造しており
    (cdf61dc で修正)、backend も `real_price >= 0` を許容していた (同時に修正) ため、
    **既存の価格履歴には ¥0 が残っている可能性がある**。読み出し側でも防御する。
    """

    @staticmethod
    def _hist(prices):
        from popcoon_core import PriceRecord
        from datetime import datetime, timedelta, timezone
        base = datetime(2026, 1, 1, tzinfo=timezone.utc)
        return [PriceRecord("k", "amazon", p + 500, p, base + timedelta(days=i))
                for i, p in enumerate(prices)]

    def test_single_zero_record_does_not_change_the_verdict(self):
        # ¥0 が 1 件混じっても正常な履歴と同じ結論になること。
        # 修正前は 95/BUY_NOW が 40/NEUTRAL に反転し、「過去最安値到達」が消えていた。
        #
        # poisoned は ¥0 を **足した** 形にする (置き換えではない)。除外後の有効列が
        # clean と厳密に同一になるので、「¥0 が無視されている」ことを直接示せる。
        # 置き換えにすると有効件数が 30 → 29 に減り、detect_dark_patterns の
        # 「30 日中」の母数を割ってスコアが変わる (それ自体は下のテストで固定する
        # 正しい挙動であって、¥0 混入の影響ではない)。
        clean = self._hist([5000] * 29 + [4900])
        poisoned = self._hist([5000] * 15 + [0] + [5000] * 14 + [4900])
        a = score_buy_timing(current=4900, list_price=6000, history=clean)
        b = score_buy_timing(current=4900, list_price=6000, history=poisoned)
        assert a.total == b.total
        assert a.verdict == b.verdict
        assert [s.name for s in a.signals] == [s.name for s in b.signals]

    def test_dropping_below_the_valid_count_threshold_withdraws_the_accusation(self):
        """有効件数が閾値を割ったら「常設セール」の指摘を取り下げる。

        detect_dark_patterns の「30 日中 90% 超が定価未満」は母数を **有効な観測数**
        で数える。30 件のうち 1 件が ¥0 なら有効 29 件で、「30 日中」を主張できない。
        ダークパターン検出は販売者を名指しする機能なので、データ不足のときに
        指摘を出さない方向へ倒すのが正しい。
        """
        clean = self._hist([5000] * 29 + [4900])
        one_replaced = self._hist([5000] * 15 + [0] + [5000] * 13 + [4900])
        a = score_buy_timing(current=4900, list_price=6000, history=clean)
        b = score_buy_timing(current=4900, list_price=6000, history=one_replaced)
        assert any("ダークパターン" in s.name for s in a.signals)
        assert not any("ダークパターン" in s.name for s in b.signals)
        # 買い時の結論自体は変わらない (指摘の有無だけが変わる)
        assert a.verdict == b.verdict

    def test_atl_signal_survives_a_zero_record(self):
        poisoned = self._hist([5000] * 15 + [0] + [5000] * 13 + [4900])
        s = score_buy_timing(current=4900, list_price=6000, history=poisoned)
        assert any("最安" in sig.name for sig in s.signals)

    def test_all_zero_history_degrades_gracefully(self):
        # 全件 ¥0 (取得が全滅) でも例外を出さず、判定不可として扱う。
        s = score_buy_timing(current=4900, list_price=6000, history=self._hist([0] * 20))
        assert s.total is not None
        assert any("判定不可" in sig.name for sig in s.signals)

    def test_negative_price_is_also_excluded(self):
        clean = self._hist([5000] * 29 + [4900])
        poisoned = self._hist([5000] * 15 + [-100] + [5000] * 14 + [4900])
        a = score_buy_timing(current=4900, list_price=6000, history=clean)
        b = score_buy_timing(current=4900, list_price=6000, history=poisoned)
        assert a.total == b.total
