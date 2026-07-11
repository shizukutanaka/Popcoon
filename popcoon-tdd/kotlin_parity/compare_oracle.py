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
from proto_conformal_interval import adaptive_conformal_margin, conformal_margin
from proto_seasonal_decomp_forecast import seasonal_decompose_forecast
from proto_cross_mall_cart import optimize_basket
from proto_darkpattern_signals import detect_dark_patterns as detect_text_patterns
from proto_seasonal_signal import seasonal_buy_signal
from popcoon_core import calculate_tco


def _parse_cart_items(enc):
    items = []
    for part in enc.split("|"):
        name, qty, opts = part.split("#")
        options = {}
        for o in opts.split(","):
            mall, price = o.split("=")
            options[mall] = float(price)
        items.append({"name": name, "qty": int(qty), "options": options})
    return items


def _parse_cart_malls(enc):
    malls = {}
    for part in enc.split("|"):
        sid, ship, free, cps = part.split("#")
        coupons = []
        if cps:
            for c in cps.split(","):
                thr, disc = c.split("=")
                coupons.append({"threshold": float(thr), "discount": float(disc)})
        malls[sid] = {"shipping": float(ship), "free_threshold": float(free), "coupons": coupons}
    return malls

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

    elif kind == "CONFORMAL":
        residuals = [float(x) for x in p[1].split(";") if x != ""]
        alpha = float(p[2])
        got = p[3]
        exp = f"{conformal_margin(residuals, alpha):.10f}"
        check(got == exp, f"conformal (n={len(residuals)},alpha={alpha})", got, exp)

    elif kind == "ADAPTIVE_CONFORMAL":
        residuals = [float(x) for x in p[1].split(";") if x != ""]
        alpha = float(p[2])
        got = p[3]
        exp = f"{adaptive_conformal_margin(residuals, alpha):.10f}"
        check(got == exp, f"adaptive_conformal (n={len(residuals)},alpha={alpha})", got, exp)

    elif kind == "SEASONAL":
        prices = [float(x) for x in p[1].split(";") if x != ""]
        horizon, period = int(p[2]), int(p[3])
        got = p[4]
        fc = seasonal_decompose_forecast(prices, horizon, period)
        exp = ";".join(f"{v:.10f}" for v in fc)
        check(got == exp, f"seasonal (n={len(prices)},h={horizon},p={period})", got, exp)

    elif kind == "TEXT":
        text = p[1]
        stock = None if p[2] == "null" else int(p[2])
        got = p[3]
        sigs = detect_text_patterns(text, stock)
        exp = ";".join(f"{w['category']}|{w['severity']}|{w['evidence']}" for w in sigs)
        check(got == exp, f"text ({text!r},stock={stock})", got, exp)

    elif kind == "TCO":
        price, category, years, intensity = int(p[1]), p[2], int(p[3]), float(p[4])
        got = p[5]
        r = calculate_tco(price, category, years, intensity)
        exp = (f"{r.consumables_total};{r.energy_total};{r.maintenance};"
               f"{r.residual_value};{r.total_tco};{r.tco_per_month}")
        check(got == exp, f"tco ({category},y={years},i={intensity})", got, exp)

    elif kind == "SDOW":
        hist = []
        for pair in p[1].split(";"):
            d, pr = pair.split(":")
            hist.append((int(d), float(pr)))
        today = int(p[2])
        got = int(p[3])
        exp = seasonal_buy_signal(hist, today)
        check(got == exp, f"sdow (n={len(hist)},today={today})", got, exp)

    elif kind == "CART":
        items = _parse_cart_items(p[1])
        malls = _parse_cart_malls(p[2])
        got = p[3]
        r = optimize_basket(items, malls)
        assign = ",".join(f"{i}={r['assignment'][i]}" for i in sorted(r["assignment"]))
        exp = (f"{r['total']:.6f}#{r['num_malls']}#{r['shipping_total']:.6f}#"
               f"{r['coupon_total']:.6f}#{assign}#{str(r.get('greedy', False)).lower()}")
        check(got == exp, f"cart ({len(items)} items)", got, exp)

    else:
        fail += 1
        print(f"UNKNOWN line: {line}")

print(f"PARITY: {ok} matched, {fail} mismatched")
sys.exit(1 if fail or ok == 0 else 0)
