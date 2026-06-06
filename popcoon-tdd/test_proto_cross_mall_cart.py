"""Tests for proto_cross_mall_cart (横断スマートカート 中核アルゴリズム検証)."""

import pytest

from proto_cross_mall_cart import optimize_basket

MALLS = {
    "amazon": {"shipping": 500.0, "free_threshold": 3000.0},
    "rakuten": {"shipping": 500.0, "free_threshold": 3000.0},
}


def test_empty_cart():
    r = optimize_basket([], MALLS)
    assert r["total"] == 0.0
    assert r["assignment"] == {}


def test_single_item_picks_cheaper_mall_incl_shipping():
    items = [{"name": "x", "options": {"amazon": 1000.0, "rakuten": 900.0}}]
    r = optimize_basket(items, MALLS)
    # rakuten 900+500=1400 < amazon 1000+500=1500
    assert r["assignment"] == {0: "rakuten"}
    assert r["total"] == 1400.0


def test_free_shipping_consolidation_beats_per_item_cheapest():
    # item0 は rakuten が安いが、amazon に寄せると送料無料ラインに到達して総額最小。
    malls = {
        "amazon": {"shipping": 800.0, "free_threshold": 2000.0},
        "rakuten": {"shipping": 800.0, "free_threshold": 5000.0},
    }
    items = [
        {"name": "a", "options": {"amazon": 1000.0, "rakuten": 900.0}},
        {"name": "b", "options": {"amazon": 1000.0, "rakuten": 1300.0}},
    ]
    r = optimize_basket(items, malls)
    # both amazon: 2000>=2000 → 送料0 → 2000（分割や rakuten 集約より安い）
    assert r["assignment"] == {0: "amazon", 1: "amazon"}
    assert r["total"] == 2000.0
    assert r["shipping_total"] == 0.0


def test_split_when_cheaper_overall():
    # 送料無料ラインに届かない → 各 item 最安モールへ分割が最適
    malls = {
        "amazon": {"shipping": 0.0, "free_threshold": 0.0},
        "rakuten": {"shipping": 0.0, "free_threshold": 0.0},
    }
    items = [
        {"name": "a", "options": {"amazon": 1000.0, "rakuten": 1200.0}},
        {"name": "b", "options": {"amazon": 1500.0, "rakuten": 1100.0}},
    ]
    r = optimize_basket(items, malls)
    assert r["assignment"] == {0: "amazon", 1: "rakuten"}
    assert r["total"] == 2100.0


def test_item_with_single_option_forced():
    items = [{"name": "x", "options": {"amazon": 1000.0}}]
    r = optimize_basket(items, MALLS)
    assert r["assignment"] == {0: "amazon"}
    # 1000 < 3000 → 送料 500
    assert r["total"] == 1500.0


def test_empty_options_raises():
    with pytest.raises(ValueError):
        optimize_basket([{"name": "x", "options": {}}], MALLS)


def test_deterministic():
    items = [
        {"name": "a", "options": {"amazon": 1000.0, "rakuten": 1000.0}},
        {"name": "b", "options": {"amazon": 1000.0, "rakuten": 1000.0}},
    ]
    assert optimize_basket(items, MALLS) == optimize_basket(items, MALLS)


def test_greedy_fallback_for_large_cart():
    # 3 モール × 14 item = 3^14 > brute_cap → 貪欲にフォールバック
    malls = {m: {"shipping": 0.0, "free_threshold": 0.0} for m in ("a", "b", "c")}
    items = [
        {"name": str(i), "options": {"a": 100.0 + i, "b": 90.0 + i, "c": 110.0 + i}}
        for i in range(14)
    ]
    r = optimize_basket(items, malls, brute_cap=1000)
    assert r.get("greedy") is True
    # 送料ゼロなので貪欲（各 item 最安 = b）が最適と一致
    assert all(m == "b" for m in r["assignment"].values())
