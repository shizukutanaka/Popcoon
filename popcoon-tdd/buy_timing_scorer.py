"""
buy_timing_scorer.py
既存3機能を統合した「買い時スコア」算出エンジン。

入力:
    current    — 現在の実質価格
    list_price — 定価
    history    — 価格履歴

出力: BuyTimingScore (0-100のスコア + 根拠signals + verdict)

構成する6シグナル:
    1. ATL近接度      (最大30点) — 過去最安に近いほど加点
    2. 価格トレンド   (最大15点) — 下降中なら加点、上昇中は減点
    3. 定価割引率     (最大15点) — list比でどれだけ安いか
    4. ボラティリティ (最大10点) — 安定推移なら加点
    5. 履歴信頼度     (最大10点) — 履歴長が長いほど確信度ボーナス
    6. ダークパターン (最大-20点) — 罠検出は大きく減点

Carmack原則: 各signalは純粋関数。副作用なし、決定論的。
"""
from dataclasses import dataclass, field
from enum import Enum
from typing import List, Optional
import sys
sys.path.insert(0, '/home/claude/popcoon-tdd')

from popcoon_core import (
    PriceRecord, predict_price, detect_dark_patterns, WarningType,
)


class TimingVerdict(Enum):
    BUY_NOW = "BUY_NOW"
    NEUTRAL = "NEUTRAL"
    WAIT    = "WAIT"


@dataclass
class TimingSignal:
    name: str
    contribution: int   # スコアへの寄与 (負値もあり)


@dataclass
class BuyTimingScore:
    total: int                 # 0-100
    verdict: TimingVerdict
    signals: List[TimingSignal]
    confidence: str            # "LOW" | "MEDIUM" | "HIGH"


# ── 定数 ────────────────────────────────────────────────────────────────────
_MIN_HISTORY = 14
_BASE_SCORE = 50  # スタート時の中立スコア


def score_buy_timing(
    current: int,
    list_price: int,
    history: List[PriceRecord],
) -> Optional[BuyTimingScore]:
    """メインエントリポイント"""
    if len(history) < _MIN_HISTORY:
        return None

    signals: List[TimingSignal] = []

    # ベーススコア (中立起点) — signalとして明示
    signals.append(TimingSignal("中立スコア", _BASE_SCORE))

    # Signal 1: ATL近接度 (最大+30, 最小-15)
    atl_sig = _signal_atl_proximity(current, history)
    signals.append(atl_sig)

    # Signal 2: 価格トレンド (±15)
    trend_sig = _signal_trend(history)
    signals.append(trend_sig)

    # Signal 3: 定価割引率 (最大+15)
    disc_sig = _signal_discount_from_list(current, list_price)
    signals.append(disc_sig)

    # Signal 4: ボラティリティ (±10)
    vol_sig = _signal_volatility(history)
    signals.append(vol_sig)

    # Signal 5: 履歴信頼度
    conf_sig = _signal_history_confidence(history)
    signals.append(conf_sig)

    # Signal 6: ダークパターン罰則 (最大-20)
    dark_sig = _signal_dark_pattern_penalty(current, list_price, history)
    if dark_sig.contribution != 0:
        signals.append(dark_sig)

    raw_sum = sum(s.contribution for s in signals)
    total = max(0, min(100, raw_sum))

    # クリップ差分を追加signalとして表現 (contributionsと totalの一致を保証)
    if total != raw_sum:
        clip_adj = total - raw_sum
        signals.append(TimingSignal("スコア正規化", clip_adj))

    verdict = _decide_verdict(total)
    confidence = _confidence_label(len(history))

    return BuyTimingScore(
        total=total, verdict=verdict, signals=signals, confidence=confidence,
    )


# ── 個別シグナル関数 ─────────────────────────────────────────────────────────
def _signal_atl_proximity(current: int, history: List[PriceRecord]) -> TimingSignal:
    prices = [r.real_price for r in history]
    low = min(prices)
    high = max(prices)
    if high == low:
        return TimingSignal("価格安定 (ATL近接判定不可)", 0)
    # current が low 付近なら加点、high 付近なら減点
    # position: 0=low, 1=high
    position = (current - low) / max(1, (high - low))
    if position <= 0:
        return TimingSignal("過去最安値到達", 30)
    if position <= 0.1:
        return TimingSignal("過去最安値圏", 22)
    if position <= 0.3:
        return TimingSignal("最安値近辺", 12)
    if position >= 0.9:
        return TimingSignal("過去最高値圏", -15)
    return TimingSignal("中間価格帯", 0)


def _signal_trend(history: List[PriceRecord]) -> TimingSignal:
    pred = predict_price(history)
    if pred is None:
        return TimingSignal("トレンド判定不可", 0)
    # predicted_30d vs current の比較
    current = history[-1].real_price
    if current == 0:
        return TimingSignal("価格ゼロ", 0)
    future_ratio = (pred.predicted_30d - current) / current
    if future_ratio > 0.05:
        return TimingSignal("価格上昇中 (待ちは不利)", 10)
    if future_ratio > 0.01:
        return TimingSignal("微上昇", 3)
    if future_ratio < -0.05:
        return TimingSignal("価格下降中 (待ちが有利)", -15)
    if future_ratio < -0.01:
        return TimingSignal("微下降", -5)
    return TimingSignal("価格横ばい", 0)


def _signal_discount_from_list(current: int, list_price: int) -> TimingSignal:
    if list_price <= 0 or list_price <= current:
        return TimingSignal("割引なし", 0)
    discount_pct = (list_price - current) / list_price * 100
    if discount_pct >= 40:
        return TimingSignal(f"定価比{int(discount_pct)}%OFF", 15)
    if discount_pct >= 25:
        return TimingSignal(f"定価比{int(discount_pct)}%OFF", 10)
    if discount_pct >= 10:
        return TimingSignal(f"定価比{int(discount_pct)}%OFF", 5)
    return TimingSignal(f"定価比{int(discount_pct)}%OFF (僅少)", 1)


def _signal_volatility(history: List[PriceRecord]) -> TimingSignal:
    prices = [r.real_price for r in history]
    if not prices:
        return TimingSignal("ボラティリティ判定不可", 0)
    mean = sum(prices) / len(prices)
    if mean == 0:
        return TimingSignal("平均価格ゼロ", 0)
    variance = sum((p - mean) ** 2 for p in prices) / len(prices)
    std = variance ** 0.5
    cv = std / mean  # coefficient of variation
    if cv < 0.02:
        return TimingSignal("極めて安定", 10)
    if cv < 0.05:
        return TimingSignal("価格安定", 5)
    if cv > 0.25:
        return TimingSignal("価格変動大", -5)
    return TimingSignal("通常の価格変動", 0)


def _signal_history_confidence(history: List[PriceRecord]) -> TimingSignal:
    n = len(history)
    if n >= 90:
        return TimingSignal("豊富な履歴", 10)
    if n >= 30:
        return TimingSignal("十分な履歴", 5)
    return TimingSignal("履歴不足", 0)


def _signal_dark_pattern_penalty(
    current: int, list_price: int, history: List[PriceRecord],
) -> TimingSignal:
    if list_price <= 0:
        list_price = None
    warnings = detect_dark_patterns(current, list_price, history)
    if not warnings:
        return TimingSignal("", 0)
    # 高深刻度毎に減点
    penalty = 0
    names = []
    for w in warnings:
        if w.type in (WarningType.ALWAYS_ON_DISCOUNT,
                      WarningType.INFLATED_LIST_PRICE,
                      WarningType.PRE_SALE_MARKUP):
            penalty -= 8
            names.append(w.label)
        elif w.type == WarningType.FAKE_SALE:
            penalty -= 4
            names.append(w.label)
    if not names:
        return TimingSignal("", 0)
    # 最大 -20 でクリップ
    penalty = max(-20, penalty)
    return TimingSignal(f"ダークパターン検出 ({'/'.join(names[:2])})", penalty)


def _decide_verdict(total: int) -> TimingVerdict:
    if total >= 70:
        return TimingVerdict.BUY_NOW
    if total <= 35:
        return TimingVerdict.WAIT
    return TimingVerdict.NEUTRAL


def _confidence_label(n: int) -> str:
    if n >= 90:
        return "HIGH"
    if n >= 30:
        return "MEDIUM"
    return "LOW"
