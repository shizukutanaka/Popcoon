"""Research prototype: cross-mall basket optimizer (機能案: 横断スマートカート).

Google I/O 2026 の Universal Cart（横断カート＋Wallet 連携でポイント/特典最適化）を見越した
Popcoon の先回り機能の中核。複数モール（Amazon/楽天/Yahoo）に分かれて買うとき、
**送料無料ライン**と**ポイント還元**を考慮して「カート全体の実質支払額」を最小化する
モール割り当てを求める純関数。ゼロ依存・決定的でオンデバイス実装可能。

Google の Universal Cart は Wallet（＝データ収集）前提だが、本機能は
オンデバイス・アカウント不要・決済情報を持たない（ディープリンクで各モールへ受け渡し）。

入力:
  items: [{"name": str, "options": {mall_id: 実質単価}}]
    実質単価 = 税込価格 - ポイント - クーポン（送料は別。PointSimulator で前計算する想定）。
  malls: {mall_id: {"shipping": 送料, "free_threshold": 送料無料ライン}}

出力: 最小実質総額となる {item_index: mall_id} 割り当てと内訳。

※ Python 参照プロトタイプ。Kotlin 移植時は出力一致パリティテストを併設すること。
"""

import itertools
from typing import Dict, List, Tuple


def _basket_cost(
    items: List[dict],
    malls: Dict[str, dict],
    combo: Tuple[str, ...],
) -> Tuple[float, dict]:
    """割り当て combo の実質総額と内訳を返す。"""
    subtotal: Dict[str, float] = {}
    for i, mall in enumerate(combo):
        subtotal[mall] = subtotal.get(mall, 0.0) + items[i]["options"][mall]

    shipping_total = 0.0
    for mall, sub in subtotal.items():
        cfg = malls.get(mall, {})
        ship = cfg.get("shipping", 0.0)
        threshold = cfg.get("free_threshold", 0.0)
        if sub > 0 and sub < threshold:
            shipping_total += ship

    total = sum(subtotal.values()) + shipping_total
    return total, {"per_mall_subtotal": subtotal, "shipping_total": shipping_total}


def optimize_basket(
    items: List[dict],
    malls: Dict[str, dict],
    brute_cap: int = 200_000,
) -> dict:
    """実質総額を最小化するモール割り当てを返す。

    小規模カートは全探索で厳密最適、大規模は item 単位の貪欲フォールバック。
    """
    n = len(items)
    if n == 0:
        return {
            "assignment": {},
            "total": 0.0,
            "per_mall_subtotal": {},
            "shipping_total": 0.0,
        }

    choice_lists = [sorted(it["options"].keys()) for it in items]
    if any(len(c) == 0 for c in choice_lists):
        raise ValueError("each item must have at least one mall option")

    size = 1
    for c in choice_lists:
        size *= len(c)

    if size <= brute_cap:
        best_cost = None
        best_combo = None
        best_detail = None
        for combo in itertools.product(*choice_lists):
            cost, detail = _basket_cost(items, malls, combo)
            if best_cost is None or cost < best_cost:
                best_cost, best_combo, best_detail = cost, combo, detail
        assignment = {i: best_combo[i] for i in range(n)}
        return {"assignment": assignment, "total": best_cost, **best_detail}

    # 貪欲フォールバック: item ごとに最安モール（送料無視）
    combo = tuple(
        min(items[i]["options"], key=lambda m: items[i]["options"][m]) for i in range(n)
    )
    cost, detail = _basket_cost(items, malls, combo)
    assignment = {i: combo[i] for i in range(n)}
    return {"assignment": assignment, "total": cost, "greedy": True, **detail}
