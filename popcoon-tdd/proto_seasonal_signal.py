"""Research prototype: day-of-week seasonal buy-timing signal (改善案 A5).

同種ソフト/arXiv 調査 (CATEGORY_RESEARCH.md cat2) で洗い出した改善案の検証用プロトタイプ。
- 出典: Best/Worst time-to-buy 特許 (USPTO 8762219) — 曜日/日付別の想定割引から買い日を推定。
        マークダウン×価格弾力性 (arXiv:2105.08313)。

価格履歴から「今日の曜日は統計的に安い/高い」を学習し、BuyTimingScorer に加える
**追加シグナル**を返す純関数。ゼロ依存・決定的でオンデバイス実装可能。

※ これは Python 側の参照プロトタイプ。Kotlin (BuyTimingScorer) へ移植する際は
   EcoEthics の教訓どおり出力一致のパリティテストを併設すること。
※ 「5と0のつく日」等の日付固定プロモは既知のドメインルール (SaleCalendar/PointSimulator)
   で扱うため、本シグナルは履歴から学習する曜日成分に限定する。
"""

from typing import List, Tuple


def seasonal_buy_signal(
    history: List[Tuple[int, float]],
    today_dow: int,
    min_history: int = 14,
    min_dow_samples: int = 2,
    max_points: int = 10,
) -> int:
    """曜日季節性の買い時シグナルを返す。

    Args:
        history: (dow, price) のリスト。dow は 0=月 .. 6=日。
        today_dow: 判定対象の曜日 (0..6)。
        min_history: これ未満の履歴では中立 (0) を返す。
        min_dow_samples: 対象曜日のサンプルがこれ未満なら曜日成分を中立化。
        max_points: 返すシグナルの絶対値上限。

    Returns:
        int: [-max_points, max_points] のシグナル。
             正 = 今日は統計的に安い (買い時寄り)、負 = 高い (様子見寄り)、0 = 中立。
    """
    if len(history) < min_history:
        return 0

    prices = [p for _, p in history]
    overall = sum(prices) / len(prices)
    if overall <= 0:
        return 0

    dow_prices = [p for d, p in history if d == today_dow]
    if len(dow_prices) < min_dow_samples:
        return 0

    dow_mean = sum(dow_prices) / len(dow_prices)
    # 相対割引: 全体平均より今日の曜日が安いほど正。
    rel = (overall - dow_mean) / overall
    # 1% の差 = 1 点、上限 max_points。
    signal = round(rel * 100)
    return max(-max_points, min(max_points, signal))
