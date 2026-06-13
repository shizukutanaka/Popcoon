"""
compare_oracle.py
Kotlin ハーネスの TSV を stdin で受け取り、同じ入力で検証済み Python オラクル
(popcoon_core / buy_timing_scorer) を再計算して照合する。入力は Kotlin の出力に
含まれるため、これは生きたクロス言語等価性チェックであり fixture drift は起き得ない。
mismatch があれば exit 1。run.sh から呼ばれる。
"""
import os
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from popcoon_core import (
    simulate_customs, score_eco_ethics, PriceRecord, Platform,
    detect_dark_patterns, predict_price,
)
from buy_timing_scorer import score_buy_timing

# Kotlin ハーネスと同一の決定論的履歴規約。
_BASE = datetime(2026, 1, 1, tzinfo=timezone.utc)


def _hist(prices, list_price):
    return [
        PriceRecord(product_key="k", platform=Platform.AMAZON, list_price=list_price,
                    real_price=p, recorded_at=_BASE + timedelta(days=i))
        for i, p in enumerate(prices)
    ]


def _prices(s):
    return [int(x) for x in s.split(",") if x != ""]


ok = 0
fail = 0


def check(cond, label, got, exp):
    global ok, fail
    if cond:
        ok += 1
    else:
        fail += 1
        print(f"MISMATCH {label}: kotlin={got} oracle={exp}")


for line in sys.stdin:
    line = line.rstrip("\n")
    if not line:
        continue
    p = line.split("\t")
    kind = p[0]

    if kind == "CUSTOMS":
        f, s, cat = int(p[1]), int(p[2]), p[3]
        jp = None if p[4] == "null" else int(p[4])
        got = (int(p[5]), int(p[6]), int(p[7]), p[8], p[9])
        r = simulate_customs(f, s, cat, jp)
        exp = (r.total_landed_cost, r.customs_duty, r.consumption_tax,
               str(r.is_tax_exempt).lower(), r.verdict.value)
        check(got == exp, f"customs ({f},{s},{cat},{jp})", got, exp)

    elif kind == "ECO":
        origin = None if p[1] == "null" else p[1]
        cat = p[2]
        certs = [] if p[3] == "" else p[3].split(";")
        got = (int(p[4]), int(p[5]), int(p[6]), f"{float(p[7]):.6f}", p[8])
        s = score_eco_ethics(origin, cat, certs)
        exp = (s.overall, s.co2_score, s.labor_score, f"{s.co2_kg:.6f}",
               s.green_alternative if s.green_alternative is not None else "null")
        check(got == exp, f"eco ({origin},{cat},{certs})", got, exp)

    elif kind == "DARKPATTERN":
        current, lp, prices = int(p[1]), int(p[2]), _prices(p[3])
        got = p[4]
        warns = detect_dark_patterns(current, lp if lp > 0 else None, _hist(prices, lp))
        exp = ",".join(sorted(w.type.name for w in warns))
        check(got == exp, f"darkpattern (cur={current},list={lp},n={len(prices)})", got, exp)

    elif kind == "PREDICT":
        current, lp, prices = int(p[1]), int(p[2]), _prices(p[3])
        pred = predict_price(_hist(prices, lp))
        if p[4] == "null":
            check(pred is None, f"predict null (n={len(prices)})", "null", pred)
        else:
            got = (int(p[4]), int(p[5]), p[6], int(p[7]), int(p[8]), p[9])
            exp = (pred.predicted_7d, pred.predicted_30d, f"{pred.buy_now_probability:.4f}",
                   pred.historic_low, pred.historic_high, pred.confidence.name) if pred else None
            check(pred is not None and got == exp, f"predict (n={len(prices)})", got, exp)

    elif kind == "BUYTIMING":
        current, lp, prices = int(p[1]), int(p[2]), _prices(p[3])
        bt = score_buy_timing(current, lp, _hist(prices, lp))
        if p[4] == "null":
            check(bt is None, f"buytiming null (n={len(prices)})", "null", bt)
        else:
            got = (int(p[4]), p[5], p[6])
            exp = (bt.total, bt.verdict.value, bt.confidence) if bt else None
            check(bt is not None and got == exp, f"buytiming (cur={current},n={len(prices)})", got, exp)

    else:
        fail += 1
        print(f"UNKNOWN line: {line}")

print(f"PARITY: {ok} matched, {fail} mismatched")
sys.exit(1 if fail or ok == 0 else 0)
