"""Research prototype: cross-mall basket optimizer (機能案: 横断スマートカート).

Google I/O 2026 の Universal Cart（横断カート＋Wallet 連携でポイント/特典最適化）を見越した
Popcoon の先回り機能の中核。複数モール（Amazon/楽天/Yahoo）に分かれて買うとき、
**送料無料ライン・モールクーポン・ポイント**を考慮して「カート全体の実質支払額」を最小化する
モール割り当てを求める純関数。ゼロ依存・決定的でオンデバイス実装可能。

仕様は UNIVERSAL_CART_SPEC.md を参照。

入力:
  items: [{"name": str, "qty": int=1, "options": {mall_id: 実質単価}}]
    実質単価 = 税込価格 - ポイント - クーポン（送料は別。PointSimulator で前計算する想定）。
  malls: {mall_id: {"shipping": 送料, "free_threshold": 送料無料ライン,
                    "coupons": [{"threshold": x, "discount": y}]（任意）}}

出力: {assignment, total, per_mall_subtotal, shipping_total, coupon_total, num_malls}

※ Python 参照プロトタイプ。Kotlin 移植時は出力一致パリティテストを併設すること。
"""

import itertools
from typing import Dict, List, Tuple


def _best_coupon(subtotal: float, coupons: List[dict]) -> float:
    """しきい値を満たすクーポンのうち最大の discount（単一適用）。"""
    best = 0.0
    for c in coupons:
        if subtotal >= c["threshold"]:
            best = max(best, c["discount"])
    return best


def _basket_cost(
    items: List[dict],
    malls: Dict[str, dict],
    combo: Tuple[str, ...],
) -> Tuple[float, dict]:
    """割り当て combo の実質総額と内訳を返す。"""
    subtotal: Dict[str, float] = {}
    for i, mall in enumerate(combo):
        qty = items[i].get("qty", 1)
        subtotal[mall] = subtotal.get(mall, 0.0) + items[i]["options"][mall] * qty

    shipping_total = 0.0
    coupon_total = 0.0
    for mall, sub in subtotal.items():
        cfg = malls.get(mall, {})
        coupon_total += _best_coupon(sub, cfg.get("coupons", []))
        ship = cfg.get("shipping", 0.0)
        threshold = cfg.get("free_threshold", 0.0)
        if sub > 0 and sub < threshold:
            shipping_total += ship

    num_malls = sum(1 for s in subtotal.values() if s > 0)
    total = sum(subtotal.values()) - coupon_total + shipping_total
    return total, {
        "per_mall_subtotal": subtotal,
        "shipping_total": shipping_total,
        "coupon_total": coupon_total,
        "num_malls": num_malls,
    }


def optimize_basket(
    items: List[dict],
    malls: Dict[str, dict],
    brute_cap: int = 200_000,
) -> dict:
    """実質総額を最小化するモール割り当てを返す。

    小規模カートは全探索で厳密最適、大規模は item 単位の貪欲フォールバック。
    同額のときは配送回数（distinct モール数）が少ない割り当てを優先（決定的）。
    """
    n = len(items)
    if n == 0:
        return {
            "assignment": {},
            "total": 0.0,
            "per_mall_subtotal": {},
            "shipping_total": 0.0,
            "coupon_total": 0.0,
            "num_malls": 0,
        }

    choice_lists = [sorted(it["options"].keys()) for it in items]
    if any(len(c) == 0 for c in choice_lists):
        raise ValueError("each item must have at least one mall option")

    size = 1
    for c in choice_lists:
        size *= len(c)

    if size <= brute_cap:
        best_key = None
        best = None
        for combo in itertools.product(*choice_lists):
            cost, detail = _basket_cost(items, malls, combo)
            # タイブレーク: (実質総額, 配送回数, combo) の辞書順最小
            key = (cost, detail["num_malls"], combo)
            if best_key is None or key < best_key:
                best_key, best = key, (combo, detail, cost)
        combo, detail, cost = best
        assignment = {i: combo[i] for i in range(n)}
        return {"assignment": assignment, "total": cost, **detail}

    # 貪欲フォールバック: item ごとに最安モール（送料・クーポン無視）
    combo = tuple(
        min(items[i]["options"], key=lambda m: items[i]["options"][m]) for i in range(n)
    )
    cost, detail = _basket_cost(items, malls, combo)
    assignment = {i: combo[i] for i in range(n)}
    return {"assignment": assignment, "total": cost, "greedy": True, **detail}


def basket_savings(
    items: List[dict],
    malls: Dict[str, dict],
    brute_cap: int = 200_000,
) -> dict:
    """最適化総額と「全部を単一モールで買った場合の最安」を比較し節約額を返す。

    Returns:
        {"optimized": float,
         "best_single_mall": Optional[Tuple[mall_id, total]],
         "saving_vs_single_mall": Optional[float]}
    """
    opt = optimize_basket(items, malls, brute_cap=brute_cap)
    if not items:
        return {"optimized": 0.0, "best_single_mall": None, "saving_vs_single_mall": None}

    n = len(items)
    best_single = None
    # 全 item が取り扱うモールのみ単一購入可能
    common = set(items[0]["options"].keys())
    for it in items[1:]:
        common &= set(it["options"].keys())
    for mall in sorted(common):
        cost, _ = _basket_cost(items, malls, (mall,) * n)
        if best_single is None or cost < best_single[1]:
            best_single = (mall, cost)

    saving = None if best_single is None else best_single[1] - opt["total"]
    return {
        "optimized": opt["total"],
        "best_single_mall": best_single,
        "saving_vs_single_mall": saving,
    }
