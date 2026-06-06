"""Tests for proto_seasonal_decomp_forecast (改善案 A1 検証)."""

import pytest

from proto_seasonal_decomp_forecast import seasonal_decompose_forecast

# 4週間、平日(月-金)=1000、週末(土日)=800 の週次パターン
WEEKEND_CHEAP = [1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 800.0, 800.0] * 4


def test_empty_history_returns_empty():
    assert seasonal_decompose_forecast([], horizon=7) == []


def test_insufficient_history_flat_fallback():
    out = seasonal_decompose_forecast([500.0, 510.0, 505.0], horizon=4, period=7)
    assert out == [505.0, 505.0, 505.0, 505.0]


def test_pure_linear_trend_continues_line():
    # price = 10*i。トレンドをそのまま外挿するはず。
    hist = [10.0 * i for i in range(28)]
    out = seasonal_decompose_forecast(hist, horizon=3, period=7)
    assert out[0] == pytest.approx(280.0, abs=1e-6)
    assert out[1] == pytest.approx(290.0, abs=1e-6)
    assert out[2] == pytest.approx(300.0, abs=1e-6)


def test_weekly_seasonality_reproduced():
    # フラットトレンド＋週次パターン → 翌週も同じ形を再現
    out = seasonal_decompose_forecast(WEEKEND_CHEAP, horizon=7, period=7)
    # index28 -> phase0 (平日), index33 -> phase5 (土)
    assert out[0] == pytest.approx(1000.0, abs=1e-6)
    assert out[5] == pytest.approx(800.0, abs=1e-6)
    assert out[6] == pytest.approx(800.0, abs=1e-6)


def test_future_weekend_cheaper_than_weekday():
    out = seasonal_decompose_forecast(WEEKEND_CHEAP, horizon=7, period=7)
    weekday = out[0]   # phase 0
    weekend = out[5]   # phase 5
    assert weekend < weekday


def test_trend_plus_seasonality():
    # 上昇トレンド(+5/日)＋週末-100 のパターン
    hist = [5.0 * i + (-100.0 if (i % 7) in (5, 6) else 0.0) for i in range(28)]
    out = seasonal_decompose_forecast(hist, horizon=7, period=7)
    # 週末位相(土t33/日t34)は同週の平日(月t28)より安い
    assert out[5] < out[0]
    assert out[6] < out[0]
    # 上昇トレンド: 予測土曜(t=33) は直近の実測土曜(i=26, 値30)より高い
    assert out[5] > hist[26]


def test_period_one_is_pure_linear():
    hist = [10.0 * i for i in range(28)]
    out = seasonal_decompose_forecast(hist, horizon=2, period=1)
    assert out[0] == pytest.approx(280.0, abs=1e-6)
    assert out[1] == pytest.approx(290.0, abs=1e-6)


def test_deterministic():
    a = seasonal_decompose_forecast(WEEKEND_CHEAP, horizon=7, period=7)
    b = seasonal_decompose_forecast(WEEKEND_CHEAP, horizon=7, period=7)
    assert a == b


def test_output_length_matches_horizon():
    out = seasonal_decompose_forecast(WEEKEND_CHEAP, horizon=5, period=7)
    assert len(out) == 5
