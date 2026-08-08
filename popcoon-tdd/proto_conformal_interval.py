"""Research prototype: split-conformal prediction interval for price forecast (改善案 A6).

CATEGORY_RESEARCH.md cat1 / RESEARCH_IMPROVEMENTS.md A6 で洗い出した改善案の検証用。
- 出典: 軽量オンライン Conformal Prediction (arXiv:2505.08158)、
        時系列CP入門 (arXiv:2511.13608)。

Split-conformal で **分布フリーの被覆保証付き区間**（「(1-alpha) の確率で ±margin」）を返す。
`PricePredictionEngine.predictionMargin` は RMSE ベースから本モジュール由来の
conformal margin へ移行済み (較正 horizon は `holt_multistep_residuals` で予測 horizon に一致させる)。
ゼロ依存・決定的でオンデバイス実装可能。

理論: 交換可能なキャリブレーション残差に対し、
  P(|Y - point| <= margin) >= 1 - alpha
が保証される。時系列は厳密には交換可能でないため、実運用では直近窓・重み付き/適応CP
(arXiv:2505.08158) を併用するとよい（本プロトタイプは基本形）。

※ Python 参照プロトタイプ。Kotlin 移植時は出力一致パリティテストを併設すること。
"""

import math
from typing import List, Optional, Tuple


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


def adaptive_conformal_margin(
    residuals: List[float],
    alpha: float = 0.1,
    eta: Optional[float] = None,
) -> float:
    """オンライン分位点追跡 (quantile tracking) — Conformal PID の P 項のみを実装
    (積分項は飽和関数のチューニングを要し誤設定時の不安定リスクがあるため見送り。
    P 項単独でも文献 (下記) の主要な利得の大部分を再現する)。

    出典: Conformal PID Control for Time Series (Angelopoulos+, NeurIPS 2023,
          arXiv:2307.16895) の quantile tracker。Adaptive Conformal Inference
          (Gibbs & Candès 2021) の分位点直接追跡版と等価。2026-07 リサーチで確認:
          分布シフト (セール期のボラティリティ急変等) に対し、静的 split-conformal
          (`conformal_margin`, 全キャリブレーション集合の分位点で順序不変) より
          直近の実績を反映できる。

    アルゴリズム: pinball loss の勾配降下と等価な更新則
        err_t = 1[|residual_t| > q_t]
        q_{t+1} = max(0, q_t + eta * (err_t - alpha))
    を残差列の時系列順に 1 パス再生する。static split-conformal の分位点で
    ウォームスタートしてから追跡することで、コールドスタート (q=0 起点だと
    序盤の残差がほぼ全て「超過」扱いになり不安定) を避ける。

    eta (ステップ幅) 省略時はデータレンジの 5% (`max(|r|) - min(|r|)`) を使う —
    ハイパーパラメータ探索を避けた決定的なデフォルト。値が大きいほど追従が速いが
    分散が増える (トレードオフは文献に準拠)。

    残差が空なら 0.0。alpha は (0,1) — 不正なら (残差が空でない限り) ValueError。
    """
    if not residuals:
        return 0.0
    if not (0.0 < alpha < 1.0):
        raise ValueError("alpha must be in (0,1)")

    abs_res = [abs(r) for r in residuals]
    if eta is None:
        data_range = max(abs_res) - min(abs_res)
        eta = max(data_range * 0.05, 1e-9)

    q = conformal_margin(residuals, alpha)  # warm start
    for r in abs_res:
        err = 1.0 if r > q else 0.0
        q = max(0.0, q + eta * (err - alpha))
    return q


def holt_multistep_residuals(
    data: List[float],
    horizon: int = 1,
    alpha_s: float = 0.3,
    beta_s: float = 0.1,
) -> List[float]:
    """Holt 線形平滑の **horizon ステップ先** 予測残差列。

    conformal の被覆保証は「キャリブレーション残差と本番の予測誤差が同分布」を前提とする。
    つまり h 日先の区間には h ステップ先残差で較正した margin が必要で、1 ステップ先残差の
    分位点を h 日先に流用すると系統的に過小被覆する (多段先の誤差は累積するため)。
    出典: Conformal Prediction Algorithms for Time Series Forecasting: Methods and
    Benchmarking (arXiv:2601.18509, 2026-01) — multi-step split conformal が
    90% 被覆を満たしつつ最良の効率を示す、というベンチマーク結果に対応する。

    アルゴリズム: 各原点 i (data[:i] まで吸収した状態) の (level, trend) から
    h ステップ先を `level + trend * h` で予測し、実測 data[i+h-1] との差を取る。
    状態更新は `_holt_linear` / Kotlin `holtLinear` と同一の再帰。

    horizon=1 のとき従来の 1 ステップ先残差列と厳密に一致する (後方互換)。
    data が 3 点未満、または h ステップ先の実測が 1 つも取れない場合は空リスト。
    """
    if horizon < 1:
        raise ValueError("horizon must be >= 1")
    if len(data) < 3:
        return []

    level = data[0]
    trend = data[1] - data[0]
    out: List[float] = []
    for i in range(1, len(data)):
        target = i + horizon - 1
        if target < len(data):
            out.append(data[target] - (level + trend * horizon))
        y = data[i]
        prev_level = level
        level = alpha_s * y + (1 - alpha_s) * (level + trend)
        trend = beta_s * (level - prev_level) + (1 - beta_s) * trend
    return out
