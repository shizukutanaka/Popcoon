"""Tests for proto_conformal_interval (改善案 A6 検証)."""

import pytest

from proto_conformal_interval import (
    adaptive_conformal_margin,
    conformal_margin,
    empirical_coverage,
    holt_multistep_residuals,
    predict_interval,
)

# 残差 (符号付き) n=11、絶対値ソート = [0,2,2,5,5,10,10,20,20,30,30]
RESIDUALS = [-30, -20, -10, -5, -2, 0, 2, 5, 10, 20, 30]


def test_empty_residuals_zero_margin():
    assert conformal_margin([], alpha=0.1) == 0.0


def test_invalid_alpha_raises():
    with pytest.raises(ValueError):
        conformal_margin(RESIDUALS, alpha=0.0)
    with pytest.raises(ValueError):
        conformal_margin(RESIDUALS, alpha=1.0)


def test_margin_exact_quantile():
    # alpha=0.1: ceil(12*0.9)=11 -> k=11>... = max = 30
    assert conformal_margin(RESIDUALS, alpha=0.1) == 30
    # alpha=0.3: ceil(12*0.7)=9 -> 9番目に小さい = 20
    assert conformal_margin(RESIDUALS, alpha=0.3) == 20


def test_margin_monotonic_in_alpha():
    # alpha が小さい(=高被覆要求)ほど margin は大きい
    m_strict = conformal_margin(RESIDUALS, alpha=0.05)
    m_loose = conformal_margin(RESIDUALS, alpha=0.4)
    assert m_strict >= m_loose


def test_coverage_meets_target():
    # 構成上、達成被覆率は 1-alpha 以上であること
    for alpha in (0.1, 0.2, 0.3):
        margin = conformal_margin(RESIDUALS, alpha=alpha)
        assert empirical_coverage(RESIDUALS, margin) >= 1.0 - alpha


def test_interval_is_symmetric_around_point():
    low, high, margin = predict_interval(1000.0, RESIDUALS, alpha=0.1)
    assert low == 1000.0 - margin
    assert high == 1000.0 + margin
    assert (high + low) / 2 == 1000.0


def test_zero_residuals_zero_margin():
    assert conformal_margin([0.0] * 20, alpha=0.1) == 0.0


def test_larger_calibration_tightens_relative_to_max():
    # 正規分布的な残差では margin < 最大絶対残差 (高被覆でなければ)
    res = [float(x) for x in range(-100, 101)]  # n=201
    margin = conformal_margin(res, alpha=0.1)
    assert margin <= 100
    # 90%被覆なので概ね 90 付近 (厳密: ceil(202*0.9)=182 番目)
    assert 85 <= margin <= 95


def test_predict_interval_contains_point():
    low, high, _ = predict_interval(500.0, RESIDUALS, alpha=0.1)
    assert low <= 500.0 <= high


# ── adaptive_conformal_margin (quantile tracking / Conformal PID P項) ──────────

def test_adaptive_empty_residuals_zero_margin():
    assert adaptive_conformal_margin([], alpha=0.1) == 0.0


def test_adaptive_invalid_alpha_raises():
    with pytest.raises(ValueError):
        adaptive_conformal_margin(RESIDUALS, alpha=0.0)
    with pytest.raises(ValueError):
        adaptive_conformal_margin(RESIDUALS, alpha=1.0)


def test_adaptive_single_residual_deterministic():
    # data_range=0 -> eta フロア 1e-9 が使われ、クラッシュせず静的分位点に極めて近い値を返す
    m = adaptive_conformal_margin([5.0], alpha=0.1)
    assert 4.999999 < m <= 5.0


def test_adaptive_reacts_to_recent_shift_more_than_static():
    # 静的分位点は順序不変なので shift/shrink で同じ値になる (順序を捨てる=直近の情報を失う)。
    shift = [2.0] * 20 + [50.0] * 10    # 直近が高ボラティリティへシフト
    shrink = [50.0] * 20 + [2.0] * 10   # 直近が沈静化
    assert conformal_margin(shift, alpha=0.1) == conformal_margin(shrink, alpha=0.1)

    # 適応版は順序に反応し、直近が沈静化した shrink の方が margin が小さくなる。
    m_shift = adaptive_conformal_margin(shift, alpha=0.1)
    m_shrink = adaptive_conformal_margin(shrink, alpha=0.1)
    assert m_shrink < m_shift


def test_adaptive_reacts_fast_to_a_recent_shock():
    # 直近1件だけ巨大な外れ値 (セール直後の急変動を模す)。静的分位点はほぼ無視するが、
    # 適応版はオンライン追跡により大きく反応する。
    shock = [1.0] * 29 + [1000.0]
    static_m = conformal_margin(shock, alpha=0.1)
    adaptive_m = adaptive_conformal_margin(shock, alpha=0.1)
    assert static_m < 5.0            # 静的分位点は外れ値をほぼ無視
    assert adaptive_m > static_m * 10  # 適応版は大幅に反応


def test_adaptive_stays_non_negative():
    # 極端に大きい eta でも margin は 0 未満にならない (max(0, ...) クランプ)。
    m = adaptive_conformal_margin([0.0] * 10, alpha=0.1, eta=100.0)
    assert m >= 0.0


def test_adaptive_deterministic():
    m1 = adaptive_conformal_margin(RESIDUALS, alpha=0.1)
    m2 = adaptive_conformal_margin(RESIDUALS, alpha=0.1)
    assert m1 == m2


# ── horizon 一致 (multi-step) 残差 ────────────────────────────────────────
# 出典: arXiv:2601.18509 (2026-01) — h 日先の区間は h ステップ先残差で較正する。

_SERIES = [1000.0, 1010.0, 990.0, 1030.0, 1005.0, 1040.0, 995.0,
           1050.0, 1020.0, 1060.0, 1010.0, 1070.0, 1030.0, 1080.0]


def _holt_1step_reference(data, alpha_s=0.3, beta_s=0.1):
    """従来の 1 ステップ先残差 (Kotlin holtResiduals の元実装と同一)。"""
    if len(data) < 3:
        return []
    level, trend = data[0], data[1] - data[0]
    out = []
    for i in range(1, len(data)):
        out.append(data[i] - (level + trend))
        y = data[i]
        prev = level
        level = alpha_s * y + (1 - alpha_s) * (level + trend)
        trend = beta_s * (level - prev) + (1 - beta_s) * trend
    return out


def test_horizon_one_matches_legacy_one_step_residuals():
    # 後方互換: horizon=1 は従来の 1 ステップ先残差列と厳密一致する。
    assert holt_multistep_residuals(_SERIES, 1) == _holt_1step_reference(_SERIES)


def test_residual_count_shrinks_with_horizon():
    # h ステップ先の実測が要るので、残差は h-1 件ずつ減る。
    n = len(_SERIES)
    assert len(holt_multistep_residuals(_SERIES, 1)) == n - 1
    assert len(holt_multistep_residuals(_SERIES, 7)) == n - 7
    assert holt_multistep_residuals(_SERIES, len(_SERIES)) == []
    assert holt_multistep_residuals(_SERIES, 99) == []


def test_multistep_residuals_are_larger_on_a_trending_series():
    # トレンドのある系列では多段先の誤差が累積し、margin が広がる。
    # これが 1 ステップ残差流用による過小被覆の正体。
    m1 = adaptive_conformal_margin(holt_multistep_residuals(_SERIES, 1))
    m7 = adaptive_conformal_margin(holt_multistep_residuals(_SERIES, 7))
    assert m7 > m1


def test_constant_series_has_zero_residuals_at_any_horizon():
    flat = [1000.0] * 20
    for h in (1, 7, 30):
        assert all(abs(r) < 1e-9 for r in holt_multistep_residuals(flat, h))


def test_perfect_linear_trend_is_predicted_exactly_at_any_horizon():
    # Holt は初期 trend を data[1]-data[0] で置くため完全直線は誤差ゼロで外挿できる。
    linear = [1000.0 + 5.0 * i for i in range(20)]
    for h in (1, 3, 7):
        assert all(abs(r) < 1e-6 for r in holt_multistep_residuals(linear, h))


def test_short_series_returns_empty():
    assert holt_multistep_residuals([], 1) == []
    assert holt_multistep_residuals([1.0, 2.0], 1) == []


def test_invalid_horizon_raises():
    with pytest.raises(ValueError):
        holt_multistep_residuals(_SERIES, 0)


def test_deterministic():
    assert holt_multistep_residuals(_SERIES, 7) == holt_multistep_residuals(_SERIES, 7)
