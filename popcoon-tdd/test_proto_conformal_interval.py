"""Tests for proto_conformal_interval (改善案 A6 検証)."""

import pytest

from proto_conformal_interval import (
    adaptive_conformal_margin,
    conformal_margin,
    empirical_coverage,
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
