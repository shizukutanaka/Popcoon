"""Research prototype: trend + seasonal linear-decomposition price forecast (改善案 A1).

CATEGORY_RESEARCH.md cat1 / RESEARCH_IMPROVEMENTS.md A1 で洗い出した改善案の検証用。
- 出典: 線形モデル分析 / DLinear・NLinear (arXiv:2403.14587)、軽量予測 (Super-Linear 2509.15105)、
        時系列予測サーベイ (arXiv:2411.05793)。

現状 `PricePredictionEngine` は Holt 線形平滑（季節性なし）。DLinear の考え方
（系列を trend と seasonal に分解し各々を線形で扱う）を、ゼロ依存・決定的・オンデバイス
向けに最小実装する。EC 価格の**週次季節性**（給料日・週末・5と0のつく日等）を捉え、
「○日後が安い」をより正確に予測することを狙う。

手順（古典的分解。中心移動平均で trend と seasonal の混線を防ぐ）:
  1. 窓長 = period の中心移動平均で trend を推定（1周期分を平均するため季節成分が相殺）。
  2. seasonal[phase] = mean(price - MA)（位相別）。総和0になるよう中心化。
  3. 価格から seasonal を引いた系列に最小二乗線形を当て trend (a*i+b) を外挿。
  4. 予測: yhat(t) = a*t + b + seasonal[t % period]。

注: 生系列への単純線形回帰だけで seasonal を出すと、週内の安値が週末に偏る等で
    見かけのトレンドが混入する（混線）。中心移動平均で先に季節成分を除く。

※ Python 参照プロトタイプ。Kotlin 移植時は出力一致パリティテストを併設すること。
"""

from typing import List, Tuple


def _linreg(xs: List[float], ys: List[float]) -> Tuple[float, float]:
    """最小二乗で (slope, intercept) を返す。分母0なら (0, mean)。"""
    n = len(xs)
    sx = sum(xs)
    sy = sum(ys)
    sxx = sum(x * x for x in xs)
    sxy = sum(x * y for x, y in zip(xs, ys))
    denom = n * sxx - sx * sx
    if denom == 0:
        return 0.0, (sy / n if n else 0.0)
    a = (n * sxy - sx * sy) / denom
    b = (sy - a * sx) / n
    return a, b


def seasonal_decompose_forecast(
    history: List[float],
    horizon: int = 7,
    period: int = 7,
    min_history: int = None,
) -> List[float]:
    """trend+seasonal 分解に基づき horizon 日先までの価格を予測する。

    Args:
        history: 価格履歴（古い→新しい、等間隔・日次想定）。
        horizon: 予測する先の本数。
        period: 季節周期（週次なら 7）。1 以下なら季節性なし（純線形）。
        min_history: これ未満なら直近値でフラット予測。既定は max(2*period, 4)。

    Returns:
        List[float]: 長さ horizon の予測値。history が空なら []。
    """
    n = len(history)
    if n == 0:
        return []
    if min_history is None:
        min_history = max(2 * period, 4)
    if n < min_history or period <= 1:
        if n < min_history:
            # フラット・フォールバック（直近値）
            return [history[-1]] * horizon
        # period<=1: 季節性なしの純線形
        a, b = _linreg(list(range(n)), history)
        return [a * (n + s) + b for s in range(horizon)]

    # 1. 中心移動平均 (窓長=period) で trend を推定。季節成分が1周期で相殺される。
    half = period // 2
    sums = [0.0] * period
    counts = [0] * period
    for i in range(n):
        start = i - half
        end = start + period
        if start >= 0 and end <= n:
            ma = sum(history[start:end]) / period
            k = i % period
            sums[k] += history[i] - ma
            counts[k] += 1
    # 2. 位相別 seasonal、総和0に中心化
    seasonal = [sums[k] / counts[k] if counts[k] else 0.0 for k in range(period)]
    mean_seasonal = sum(seasonal) / period
    seasonal = [s - mean_seasonal for s in seasonal]
    # 3. 季節除去系列に線形トレンドを当てる
    deseason = [history[i] - seasonal[i % period] for i in range(n)]
    a, b = _linreg(list(range(n)), deseason)
    # 4. 予測
    out = []
    for s in range(horizon):
        t = n + s
        out.append(a * t + b + seasonal[t % period])
    return out
