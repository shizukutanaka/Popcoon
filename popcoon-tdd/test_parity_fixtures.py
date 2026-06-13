"""
test_parity_fixtures.py
parity_fixtures.json を検証済みオラクルと突き合わせる能動的セルフチェック。

役割:
  1. fixture が古くならないことを保証 (オラクルを変えたら fixture も更新が必要)。
  2. Kotlin 側パリティテストが消費する「契約」が常に正しいことを担保。

fixture は gen_parity_fixtures.py で生成する。両者がズレたらこのテストが落ちる。
"""
import json
import os
from datetime import datetime, timedelta, timezone

import pytest

from popcoon_core import PriceRecord, Platform, simulate_customs, score_eco_ethics
from buy_timing_scorer import score_buy_timing

FIXTURE_PATH = os.path.join(os.path.dirname(__file__), "parity_fixtures.json")
_BASE = datetime(2026, 1, 1, tzinfo=timezone.utc)


@pytest.fixture(scope="module")
def fixtures():
    assert os.path.exists(FIXTURE_PATH), \
        "parity_fixtures.json 不在 — `python3 gen_parity_fixtures.py` で生成すること"
    with open(FIXTURE_PATH, encoding="utf-8") as f:
        return json.load(f)


def _hist(prices, list_price):
    return [
        PriceRecord(product_key="k", platform=Platform.AMAZON, list_price=list_price,
                    real_price=p, recorded_at=_BASE + timedelta(days=i))
        for i, p in enumerate(prices)
    ]


def test_customs_fixtures_match_oracle(fixtures):
    for c in fixtures["customs"]:
        i, e = c["input"], c["expect"]
        r = simulate_customs(i["foreign"], i["ship"], i["category"], i["japan_best"])
        assert r.total_landed_cost == e["total_landed_cost"], f"customs {i}"
        assert r.customs_duty == e["customs_duty"], f"customs duty {i}"
        assert r.consumption_tax == e["consumption_tax"], f"customs ctax {i}"
        assert r.is_tax_exempt == e["is_tax_exempt"], f"customs exempt {i}"
        assert r.verdict.value == e["verdict"], f"customs verdict {i}"


def test_eco_fixtures_match_oracle(fixtures):
    for c in fixtures["eco_ethics"]:
        i, e = c["input"], c["expect"]
        s = score_eco_ethics(i["origin"], i["category"], i["certifications"])
        assert s.overall == e["overall"], f"eco overall {i}"
        assert s.co2_score == e["co2_score"], f"eco co2 {i}"
        assert s.labor_score == e["labor_score"], f"eco labor {i}"
        assert round(s.co2_kg, 6) == e["co2_kg"], f"eco co2kg {i}"
        assert s.green_alternative == e["green_alternative"], f"eco green {i}"


def test_buy_timing_fixtures_match_oracle(fixtures):
    for c in fixtures["buy_timing"]:
        i, e = c["input"], c["expect"]
        s = score_buy_timing(i["current"], i["list_price"], _hist(i["prices"], i["list_price"]))
        if e is None:
            assert s is None, f"buy_timing expected None {i}"
        else:
            assert s is not None, f"buy_timing unexpected None {i}"
            assert s.total == e["total"], f"buy_timing total {i}"
            assert s.verdict.value == e["verdict"], f"buy_timing verdict {i}"
            assert s.confidence == e["confidence"], f"buy_timing confidence {i}"


def test_fixture_has_all_sections(fixtures):
    for section in ("customs", "eco_ethics", "buy_timing"):
        assert section in fixtures and len(fixtures[section]) > 0
