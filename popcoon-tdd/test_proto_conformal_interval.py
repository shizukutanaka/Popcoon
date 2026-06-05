"""Tests for proto_conformal_interval (改善案 A6 検証)."""

import pytest

from proto_conformal_interval import (
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
