"""
gen_parity_fixtures.py
クロス言語パリティの「契約」を生成する。

問題: プロジェクトの看板は "Python parity" (Kotlin 移植が Python と一致) だが、
それを強制する機構が無かった (test_differential.py は Python 同士の比較)。
実際、CustomsSimulator は verdict 分岐順が乖離し、Kotlin テストがそのバグを
固定していた (Tier 9 参照)。

解決の第一歩: 検証済み Python オラクルから言語中立な JSON fixture を書き出す。
固定入力 → 期待出力。Kotlin 側のパリティテストが同じ JSON を読み込んで照合すれば、
"parity" は文書上の主張ではなく実行可能な契約になる。

このスクリプトが生成する parity_fixtures.json は:
  - Python: test_parity_fixtures.py が毎回 read-and-verify (オラクルとの整合を保証)
  - Kotlin: (今後) app/src/test 配下のパリティテストが同 JSON を消費して照合

対象は時刻・履歴に依存しないスカラー関数を優先しつつ、看板機能 (buy_timing) も
価格列だけで再現できる形で含める。Kotlin 側も同じ決定論的構築をすれば一致する。
"""
import json
import os
from datetime import datetime, timedelta, timezone

from popcoon_core import PriceRecord, Platform, simulate_customs, score_eco_ethics
from buy_timing_scorer import score_buy_timing

FIXTURE_PATH = os.path.join(os.path.dirname(__file__), "parity_fixtures.json")

# 決定論的な履歴構築規約 (Kotlin 側も同一にすること):
#   product_key="k", platform=AMAZON, list_price=<case>, recorded_at = 2026-01-01 + i 日 (UTC),
#   real_price = prices[i]
_BASE = datetime(2026, 1, 1, tzinfo=timezone.utc)


def _hist(prices, list_price):
    return [
        PriceRecord(
            product_key="k",
            platform=Platform.AMAZON,
            list_price=list_price,
            real_price=p,
            recorded_at=_BASE + timedelta(days=i),
        )
        for i, p in enumerate(prices)
    ]


def _customs_cases():
    cases = [
        # (foreign, ship, category, japan_best)
        (10_000, 5_000, "衣類", None),
        (20_000, 5_000, "靴", None),
        (50_000, 5_000, "電子機器", None),
        (10_000, 2_000, "食品", 50_000),    # 免税級の掘り出し物 -> CHEAPER
        (20_000, 2_000, "食品", 40_000),    # 中途半端な節約 -> NOT_RECOMMENDED
        (40_000, 5_000, "食品", 30_000),    # 国内以上 -> MORE_EXPENSIVE
        (20_000, 2_000, "衣類", 40_000),    # 非食品 同帯 -> CHEAPER
        (10_000, 6_666, "衣類", None),      # 免税ぴったり
    ]
    out = []
    for foreign, ship, cat, jp in cases:
        r = simulate_customs(foreign, ship, cat, jp)
        out.append({
            "input": {"foreign": foreign, "ship": ship, "category": cat, "japan_best": jp},
            "expect": {
                "total_landed_cost": r.total_landed_cost,
                "customs_duty": r.customs_duty,
                "consumption_tax": r.consumption_tax,
                "is_tax_exempt": r.is_tax_exempt,
                "verdict": r.verdict.value,
            },
        })
    return out


def _eco_cases():
    cases = [
        # (origin, category, certifications)
        ("CN", "smartphone", []),
        ("JP", "laptop", []),
        ("DE", "tv", ["green-cert"]),
        ("VN", "tshirt", []),
        (None, "smartphone", []),           # 未知の国 -> デフォルト係数
        ("US", "unknown_category", []),      # 未知カテゴリ -> デフォルト base_co2
    ]
    out = []
    for origin, cat, certs in cases:
        s = score_eco_ethics(origin, cat, certs)
        out.append({
            "input": {"origin": origin, "category": cat, "certifications": certs},
            "expect": {
                "overall": s.overall,
                "co2_score": s.co2_score,
                "labor_score": s.labor_score,
                "co2_kg": round(s.co2_kg, 6),
                "green_alternative": s.green_alternative,
            },
        })
    return out


def _buy_timing_cases():
    cases = [
        # (current, list_price, prices)
        (10_000, 12_000, [10_000] * 90),
        (12_100, 20_000, list(range(15_000, 12_000, -100))),   # 30 点 降順, current=最安
        (11_300, 14_000, [10_000 + i * 100 for i in range(14)]),  # 14 点 昇順, current=最高
        (10_000, 12_000, [10_000] * 13),                        # 履歴不足 -> null
    ]
    out = []
    for current, lp, prices in cases:
        s = score_buy_timing(current, lp, _hist(prices, lp))
        out.append({
            "input": {"current": current, "list_price": lp, "prices": prices},
            "expect": None if s is None else {
                "total": s.total,
                "verdict": s.verdict.value,
                "confidence": s.confidence,
            },
        })
    return out


def build():
    return {
        "_doc": "Cross-language parity contract generated from popcoon_core (verified oracle). "
                "Kotlin parity tests must reproduce these outputs. "
                "History build rule: product_key='k', AMAZON, recorded_at=2026-01-01+i days UTC.",
        "customs": _customs_cases(),
        "eco_ethics": _eco_cases(),
        "buy_timing": _buy_timing_cases(),
    }


if __name__ == "__main__":
    data = build()
    with open(FIXTURE_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    n = sum(len(data[k]) for k in ("customs", "eco_ethics", "buy_timing"))
    print(f"wrote {FIXTURE_PATH}: {n} cases")
