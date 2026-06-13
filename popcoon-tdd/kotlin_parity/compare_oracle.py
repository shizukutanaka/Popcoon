"""
compare_oracle.py
Kotlin ハーネスの TSV を stdin で受け取り、同じ入力で検証済み Python オラクル
(popcoon_core) を再計算して照合する。入力は Kotlin の出力に含まれるため、
これは生きたクロス言語等価性チェックであり fixture drift は起き得ない。

mismatch があれば exit 1。run.sh から呼ばれる。
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from popcoon_core import simulate_customs, score_eco_ethics

ok = 0
fail = 0
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
        if got == exp:
            ok += 1
        else:
            fail += 1
            print(f"MISMATCH customs ({f},{s},{cat},{jp}): kotlin={got} oracle={exp}")
    elif kind == "ECO":
        origin = None if p[1] == "null" else p[1]
        cat = p[2]
        certs = [] if p[3] == "" else p[3].split(";")
        got = (int(p[4]), int(p[5]), int(p[6]), round(float(p[7]), 6), p[8])
        s = score_eco_ethics(origin, cat, certs)
        exp = (s.overall, s.co2_score, s.labor_score, round(s.co2_kg, 6),
               s.green_alternative if s.green_alternative is not None else "null")
        if got == exp:
            ok += 1
        else:
            fail += 1
            print(f"MISMATCH eco ({origin},{cat},{certs}): kotlin={got} oracle={exp}")
    else:
        fail += 1
        print(f"UNKNOWN line: {line}")

print(f"PARITY: {ok} matched, {fail} mismatched")
sys.exit(1 if fail or ok == 0 else 0)
