"""Research prototype: split-conformal prediction interval for price forecast (改善案 A6).

CATEGORY_RESEARCH.md cat1 / RESEARCH_IMPROVEMENTS.md A6 で洗い出した改善案の検証用。
- 出典: 軽量オンライン Conformal Prediction (arXiv:2505.08158)、
        時系列CP入門 (arXiv:2511.13608)。

現状 `PricePredictionEngine.predictionMargin` は RMSE ベースで被覆保証がない。
Split-conformal で **分布フリーの被覆保証付き区間**（「(1-alpha) の確率で ±margin」）を返す。
ゼロ依存・決定的でオンデバイス実装可能。

理論: 交換可能なキャリブレーション残差に対し、
  P(|Y - point| <= margin) >= 1 - alpha
が保証される。時系列は厳密には交換可能でないため、実運用では直近窓・重み付き/適応CP
(arXiv:2505.08158) を併用するとよい（本プロトタイプは基本形）。

※ Python 参照プロトタイプ。Kotlin 移植時は出力一致パリティテストを併設すること。
"""

import math
from typing import List, Tuple


def conformal_margin(
    calibration_residuals: List[float],
    alpha: float = 0.1,
) -> float:
    """絶対残差の split-conformal 分位点 (= 区間半幅 margin) を返す。

    Args:
        calibration_residuals: キャリブレーション集合の残差 (符号付きでも可、絶対値を取る)。
        alpha: 許容誤り率 (0<alpha<1)。被覆目標は 1-alpha。

    Returns:
        float: margin (>=0)。残差が空なら 0.0。
    """
    if not calibration_residuals:
        return 0.0
    if not (0.0 < alpha < 1.0):
        raise ValueError("alpha must be in (0,1)")

    abs_res = sorted(abs(r) for r in calibration_residuals)
    n = len(abs_res)
    # 1-indexed の k 番目を採用。k>n のときは有限近似として最大値。
    k = math.ceil((n + 1) * (1.0 - alpha))
    if k > n:
        return abs_res[-1]
    return abs_res[k - 1]


def predict_interval(
    point: float,
    calibration_residuals: List[float],
    alpha: float = 0.1,
) -> Tuple[float, float, float]:
    """点予測 point に対する被覆保証付き区間 (low, high, margin) を返す。"""
    margin = conformal_margin(calibration_residuals, alpha)
    return (point - margin, point + margin, margin)


def empirical_coverage(
    calibration_residuals: List[float],
    margin: float,
) -> float:
    """与えた margin が残差をどれだけ被覆するか (0..1)。検証用。"""
    if not calibration_residuals:
        return 0.0
    covered = sum(1 for r in calibration_residuals if abs(r) <= margin)
    return covered / len(calibration_residuals)
