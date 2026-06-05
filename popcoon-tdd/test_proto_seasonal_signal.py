"""Tests for proto_seasonal_signal (改善案 A5 検証)."""

from proto_seasonal_signal import seasonal_buy_signal


def _four_weeks_weekend_cheap():
    """4週間(28日)の履歴。Sat(5)/Sun(6) は 800、平日は 1000。"""
    return [(i % 7, 800.0 if (i % 7) in (5, 6) else 1000.0) for i in range(28)]


def test_insufficient_history_is_neutral():
    assert seasonal_buy_signal([(0, 1000.0)] * 5, today_dow=0) == 0


def test_flat_prices_are_neutral():
    hist = [(i % 7, 1000.0) for i in range(28)]
    assert seasonal_buy_signal(hist, today_dow=3) == 0


def test_zero_or_negative_mean_is_neutral():
    hist = [(i % 7, 0.0) for i in range(28)]
    assert seasonal_buy_signal(hist, today_dow=3) == 0


def test_cheap_weekday_gives_positive_signal_capped():
    # 土曜は全体平均(≈942.9)より約15%安い → 上限の +10 にクランプ
    hist = _four_weeks_weekend_cheap()
    assert seasonal_buy_signal(hist, today_dow=5) == 10


def test_expensive_weekday_gives_negative_signal():
    # 月曜は1000で全体平均より約6%高い → 約 -6
    hist = _four_weeks_weekend_cheap()
    sig = seasonal_buy_signal(hist, today_dow=0)
    assert sig < 0
    assert sig == -6


def test_sparse_dow_samples_are_neutral():
    # 対象曜日のサンプルが1件のみ → 中立
    hist = [(1, 1000.0) for _ in range(13)] + [(4, 700.0)]
    assert seasonal_buy_signal(hist, today_dow=4) == 0


def test_signal_within_bounds():
    hist = [(i % 7, 100.0 if (i % 7) == 2 else 5000.0) for i in range(28)]
    sig = seasonal_buy_signal(hist, today_dow=2)
    assert -10 <= sig <= 10
    assert sig == 10  # 大幅に安い曜日は上限


def test_deterministic():
    hist = _four_weeks_weekend_cheap()
    a = seasonal_buy_signal(hist, today_dow=6)
    b = seasonal_buy_signal(hist, today_dow=6)
    assert a == b
