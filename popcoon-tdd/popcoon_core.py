"""
popcoon_core.py
Kotlin純粋関数ロジックの Python port — TDD実行可能版。

Popcoonの核心は全てプラットフォーム非依存。
Kotlinで書いたロジックを1:1でportしてpytestで実行する。

これは「ビジネスロジックとプラットフォームの分離」の実演でもある。
Android固有機能 (Compose/Room/Hilt) を剥がせば核心が残る。
"""
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional, List, Dict, Tuple
import math
import statistics
from datetime import datetime, timedelta, timezone


# ───────────────────────────────────────────────────────────────────────────
# コアモデル
# ───────────────────────────────────────────────────────────────────────────
class Platform(Enum):
    AMAZON = ("amazon", "Amazon", 0xFFFF9900)
    RAKUTEN = ("rakuten", "楽天", 0xFFBF0000)
    YAHOO = ("yahoo", "Yahoo", 0xFFFF0033)

    def __init__(self, pid, display, color):
        self.id = pid
        self.display = display
        self.color = color

    @classmethod
    def from_id_or_none(cls, pid: str) -> Optional["Platform"]:
        for p in cls:
            if p.id == pid:
                return p
        return None

    @classmethod
    def from_id(cls, pid: str) -> "Platform":
        """NPE防止: 不明なら AMAZON を返す"""
        return cls.from_id_or_none(pid) or cls.AMAZON


@dataclass(frozen=True)
class Product:
    sku: str
    title: str
    platform: Platform
    real_price: int
    list_price: int
    shipping_fee: int = 0
    points_back: int = 0
    subscribe_price: Optional[int] = None
    url: str = ""
    rating: Optional[float] = None
    trust_score: int = 50

    @property
    def total_price(self) -> int:
        return self.real_price + self.shipping_fee - self.points_back

    @property
    def key(self) -> str:
        return f"{self.platform.id}:{self.sku}"


@dataclass(frozen=True)
class PriceRecord:
    product_key: str
    platform: str
    list_price: int
    real_price: int
    recorded_at: datetime


# ───────────────────────────────────────────────────────────────────────────
# 価格予測エンジン (Holt's linear)
# ───────────────────────────────────────────────────────────────────────────
class Confidence(Enum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    UNKNOWN = "UNKNOWN"


@dataclass
class Prediction:
    current_price: int
    predicted_7d: int
    predicted_30d: int
    buy_now_probability: float
    historic_low: int
    historic_high: int
    seasonal_note: Optional[str]
    confidence: Confidence


def predict_price(records: List[PriceRecord]) -> Optional[Prediction]:
    """Holt's linear smoothing による価格予測"""
    if len(records) < 14:
        return None

    data = [float(r.real_price) for r in records]
    cleaned = _remove_outliers_iqr(data)
    if len(cleaned) < 2:
        return None

    level, trend = _holt_linear(cleaned, alpha=0.3, beta=0.1)
    pred_7 = max(0, int(level + trend * 7))
    pred_30 = max(0, int(level + trend * 30))

    current = records[-1].real_price
    low = min(cleaned)
    high = max(cleaned)

    # 買い時確率: percentile + trend
    pct = sum(1 for p in cleaned if p >= current) / len(cleaned)
    trend_boost = 0.3 if trend < 0 else 0.0
    buy_prob = min(1.0, max(0.0, pct * 0.5 + trend_boost))

    conf = (
        Confidence.HIGH if len(records) >= 90
        else Confidence.MEDIUM if len(records) >= 30
        else Confidence.LOW
    )

    return Prediction(
        current_price=current,
        predicted_7d=pred_7,
        predicted_30d=pred_30,
        buy_now_probability=buy_prob,
        historic_low=int(low),
        historic_high=int(high),
        seasonal_note=None,
        confidence=conf,
    )


def _holt_linear(data: List[float], alpha: float, beta: float) -> Tuple[float, float]:
    L = data[0]
    T = data[1] - data[0] if len(data) >= 2 else 0.0
    for y in data[1:]:
        prev_L = L
        L = alpha * y + (1 - alpha) * (L + T)
        T = beta * (L - prev_L) + (1 - beta) * T
    return L, T


def _remove_outliers_iqr(data: List[float]) -> List[float]:
    if len(data) < 4:
        return data
    sorted_d = sorted(data)
    q1 = sorted_d[len(sorted_d) // 4]
    q3 = sorted_d[len(sorted_d) * 3 // 4]
    iqr = q3 - q1
    return [x for x in data if (q1 - 1.5 * iqr) <= x <= (q3 + 1.5 * iqr)]


# ───────────────────────────────────────────────────────────────────────────
# 関税シミュレーター
# ───────────────────────────────────────────────────────────────────────────
class CustomsVerdict(Enum):
    CHEAPER = "CHEAPER"
    COMPARABLE = "COMPARABLE"
    MORE_EXPENSIVE = "MORE_EXPENSIVE"
    NOT_RECOMMENDED = "NOT_RECOMMENDED"


@dataclass
class CustomsResult:
    foreign_price: int
    shipping_fee: int
    dutiable_value: int
    customs_duty: int
    consumption_tax: int
    handling_fee: int
    total_landed_cost: int
    is_tax_exempt: bool
    verdict: CustomsVerdict


DUTY_RATES = {
    "衣類": 0.12,
    "靴": 0.30,
    "バッグ": 0.08,
    "電子機器": 0.00,  # ITA 無税
    "カメラ": 0.00,
    "おもちゃ": 0.00,
    "スポーツ用品": 0.04,
    "化粧品": 0.03,
    "食品": 0.20,
    "その他": 0.06,
}

TAX_EXEMPT_THRESHOLD = 16_666

# 消費税率: 標準10%、軽減税率8% (酒類・外食を除く飲食料品、2019年10月〜)。
# カテゴリ体系に酒類/外食の区別が無いため「食品」カテゴリ全体に軽減税率を適用する
# (酒類の混入は既知の簡略化 — UI 側で「概算」であることを開示する)。
STANDARD_TAX_RATE = 0.10
REDUCED_TAX_RATE = 0.08


def simulate_customs(
    foreign_price_jpy: int,
    shipping_jpy: int,
    category: str = "その他",
    japan_best_price: Optional[int] = None,
) -> CustomsResult:
    """日本税関ルールでの着払い価格計算"""
    # 負数ガード
    foreign_price_jpy = max(0, foreign_price_jpy)
    shipping_jpy = max(0, shipping_jpy)

    dutiable = foreign_price_jpy + shipping_jpy
    is_exempt = dutiable <= TAX_EXEMPT_THRESHOLD
    duty_rate = DUTY_RATES.get(category, DUTY_RATES["その他"])
    tax_rate = REDUCED_TAX_RATE if category == "食品" else STANDARD_TAX_RATE

    duty = 0 if is_exempt else int(dutiable * duty_rate)
    ctax = 0 if is_exempt else int((dutiable + duty) * tax_rate)
    fee = 0 if is_exempt else 200

    total = foreign_price_jpy + shipping_jpy + duty + ctax + fee

    if japan_best_price is None:
        verdict = CustomsVerdict.CHEAPER
    elif is_exempt and total < japan_best_price * 0.7:
        verdict = CustomsVerdict.CHEAPER
    elif total >= japan_best_price:
        verdict = CustomsVerdict.MORE_EXPENSIVE
    elif total >= japan_best_price * 0.9:
        verdict = CustomsVerdict.COMPARABLE
    elif category in ("食品", "化粧品"):
        verdict = CustomsVerdict.NOT_RECOMMENDED
    else:
        verdict = CustomsVerdict.CHEAPER

    return CustomsResult(
        foreign_price=foreign_price_jpy,
        shipping_fee=shipping_jpy,
        dutiable_value=dutiable,
        customs_duty=duty,
        consumption_tax=ctax,
        handling_fee=fee,
        total_landed_cost=total,
        is_tax_exempt=is_exempt,
        verdict=verdict,
    )


# ───────────────────────────────────────────────────────────────────────────
# TCO計算
# ───────────────────────────────────────────────────────────────────────────
@dataclass
class ConsumableItem:
    name: str
    price_per_unit: int
    units_per_year: float

    @property
    def yearly_total(self) -> int:
        return int(self.price_per_unit * self.units_per_year)


@dataclass
class TCOResult:
    purchase_price: int
    consumables_total: int
    energy_total: int
    maintenance: int
    residual_value: int
    total_tco: int
    tco_per_month: int
    vs_alternative: Optional[Tuple[str, int, int]]  # label, alt_tco, savings


CONSUMABLES_DB = {
    "inkjet_printer": lambda intensity: [
        ConsumableItem("インク黒", 1800, 6.0 * intensity),
        ConsumableItem("インクカラー", 2200, 4.0 * intensity),
        ConsumableItem("用紙500枚", 800, 2.0 * intensity),
    ],
    "laser_printer": lambda intensity: [
        ConsumableItem("トナー", 6000, 1.5 * intensity),
        ConsumableItem("ドラム", 8000, 0.33),
        ConsumableItem("用紙500枚", 600, 3.0 * intensity),
    ],
    "coffee_capsule": lambda intensity: [
        ConsumableItem("カプセル", 80, 365.0 * intensity),
    ],
    "generic": lambda _: [],
}

ENERGY_DB = {
    "inkjet_printer": (15, 0.5),  # (watts, hours_per_day)
    "laser_printer": (400, 0.5),
    "laptop": (45, 6.0),
    "refrigerator": (35, 24.0),
    "air_conditioner": (700, 8.0),
}

RESIDUAL_RATE_DB = {
    "smartphone": lambda y: max(0.0, 0.5 - y * 0.12),
    "laptop": lambda y: max(0.0, 0.4 - y * 0.08),
    "inkjet_printer": lambda y: max(0.0, 0.05 - y * 0.01),
    "generic": lambda y: max(0.0, 0.05 - y * 0.01),
}


def calculate_tco(
    purchase_price: int,
    category: str,
    years: int = 5,
    intensity: float = 1.0,
) -> TCOResult:
    """5年TCO (Total Cost of Ownership) 計算"""
    consumables_fn = CONSUMABLES_DB.get(category, CONSUMABLES_DB["generic"])
    items = consumables_fn(intensity)
    consumables_per_year = sum(i.yearly_total for i in items)
    consumables_total = consumables_per_year * years

    wattage, hours = ENERGY_DB.get(category, (0, 0))
    energy_yearly = int(wattage * hours * 365 / 1000 * 27)
    energy_total = energy_yearly * years

    if 4 <= years <= 6:
        maintenance = purchase_price // 10
    elif years >= 7:
        maintenance = purchase_price // 6
    else:
        maintenance = 0

    residual_rate = RESIDUAL_RATE_DB.get(category, RESIDUAL_RATE_DB["generic"])(years)
    residual = int(purchase_price * residual_rate)

    tco = purchase_price + consumables_total + energy_total + maintenance - residual

    # インクジェット vs タンク式の代替比較
    vs_alt = None
    if category == "inkjet_printer":
        alt_tco = purchase_price * 3 + 10_000 + 3_000 * years
        vs_alt = ("インクタンク式", alt_tco, tco - alt_tco)

    return TCOResult(
        purchase_price=purchase_price,
        consumables_total=consumables_total,
        energy_total=energy_total,
        maintenance=maintenance,
        residual_value=residual,
        total_tco=tco,
        tco_per_month=tco // (years * 12) if years > 0 else tco,
        vs_alternative=vs_alt,
    )


# ───────────────────────────────────────────────────────────────────────────
# ダークパターン検出
# ───────────────────────────────────────────────────────────────────────────
class WarningType(Enum):
    ALWAYS_ON_DISCOUNT = "ALWAYS_ON_DISCOUNT"
    INFLATED_LIST_PRICE = "INFLATED_LIST_PRICE"
    PRE_SALE_MARKUP = "PRE_SALE_MARKUP"
    CHARM_PRICING = "CHARM_PRICING"
    FAKE_SALE = "FAKE_SALE"


class Severity(Enum):
    LOW = 1
    MEDIUM = 2
    HIGH = 3


@dataclass
class PsychWarning:
    type: WarningType
    label: str
    severity: Severity


def detect_dark_patterns(
    current_price: int,
    list_price: Optional[int],
    history: List[PriceRecord],
) -> List[PsychWarning]:
    warnings = []

    # 1. 常設セール (90%以上の期間割引中)
    if list_price and list_price > current_price and len(history) >= 30:
        below = sum(1 for r in history if r.real_price < list_price)
        if below / len(history) > 0.90:
            warnings.append(PsychWarning(
                WarningType.ALWAYS_ON_DISCOUNT, "常設セール", Severity.HIGH))

    # 2. 参考価格詐欺
    if list_price and history:
        actual_high = max(r.real_price for r in history)
        if list_price > actual_high * 1.5:
            warnings.append(PsychWarning(
                WarningType.INFLATED_LIST_PRICE, "参考価格誇張", Severity.HIGH))

    # 3. セール前値上げ
    if len(history) >= 14:
        recent = history[-7:]
        prev = history[-14:-7]
        recent_avg = sum(r.real_price for r in recent) / 7
        prev_avg = sum(r.real_price for r in prev) / 7
        if recent_avg > prev_avg * 1.10 and list_price and current_price < list_price:
            warnings.append(PsychWarning(
                WarningType.PRE_SALE_MARKUP, "セール前値上げ", Severity.HIGH))

    # 4. 端数価格
    last_two = current_price % 100
    if 80 <= last_two <= 99:
        warnings.append(PsychWarning(
            WarningType.CHARM_PRICING, "端数価格", Severity.LOW))

    return warnings


# ───────────────────────────────────────────────────────────────────────────
# Trie (オフライン autocomplete)
# ───────────────────────────────────────────────────────────────────────────
class TrieNode:
    __slots__ = ("children", "words")

    def __init__(self):
        self.children = {}
        self.words = []


class Trie:
    def __init__(self):
        self.root = TrieNode()
        self._size = 0

    def insert(self, word: str) -> None:
        if not word:
            return
        node = self.root
        for ch in word:
            if ch not in node.children:
                node.children[ch] = TrieNode()
            node = node.children[ch]
        if word not in node.words:
            node.words.append(word)
            self._size += 1

    def suggest(self, prefix: str, limit: int = 8) -> List[str]:
        node = self.root
        for ch in prefix:
            if ch not in node.children:
                return []
            node = node.children[ch]
        return self._collect(node, limit)

    def _collect(self, node: TrieNode, limit: int) -> List[str]:
        from collections import deque
        result = []
        queue = deque([node])
        while queue and len(result) < limit:
            cur = queue.popleft()  # O(1) — popから popleftに変更
            for w in cur.words:
                if len(result) < limit:
                    result.append(w)
            queue.extend(cur.children.values())
        return result

    def size(self) -> int:
        return self._size


# ───────────────────────────────────────────────────────────────────────────
# アラート評価エンジン
# ───────────────────────────────────────────────────────────────────────────
@dataclass
class AlertCondition:
    """AND/OR/NOT + 葉ノードの複合条件"""
    op: str  # "AND" | "OR" | "NOT" | leaf名
    children: List["AlertCondition"] = field(default_factory=list)
    value: Optional[object] = None


def eval_condition(cond: AlertCondition, product: Product) -> bool:
    if cond.op == "AND":
        return all(eval_condition(c, product) for c in cond.children)
    if cond.op == "OR":
        return any(eval_condition(c, product) for c in cond.children)
    if cond.op == "NOT":
        return not eval_condition(cond.children[0], product)
    if cond.op == "PRICE_BELOW":
        return product.total_price <= cond.value
    if cond.op == "PRICE_ABOVE":
        return product.total_price >= cond.value
    if cond.op == "FREE_SHIPPING":
        return (product.shipping_fee == 0) == bool(cond.value)
    if cond.op == "PLATFORM_IS":
        return product.platform == cond.value
    if cond.op == "TRUST_AT_LEAST":
        return product.trust_score >= cond.value
    if cond.op == "DISCOUNT_AT_LEAST":
        if product.list_price <= 0:
            return False
        pct = (product.list_price - product.real_price) * 100 / product.list_price
        return pct >= cond.value
    return False


# ───────────────────────────────────────────────────────────────────────────
# CO2 / 倫理スコア
# ───────────────────────────────────────────────────────────────────────────
CO2_BY_COUNTRY = {
    "JP": 0.45, "DE": 0.30, "US": 0.38, "CN": 0.78,
    "VN": 0.65, "BD": 0.70, "IN": 0.72, "KR": 0.50,
}

LABOR_BY_COUNTRY = {
    "JP": 82, "DE": 90, "US": 78, "CN": 52,
    "VN": 48, "BD": 40, "IN": 55, "KR": 72,
}

CO2_BY_CATEGORY = {
    "smartphone": 70.0,
    "laptop": 300.0,
    "tv": 400.0,
    "tshirt": 8.0,
}


@dataclass
class EcoScore:
    overall: int
    co2_score: int
    labor_score: int
    co2_kg: float
    green_alternative: Optional[str]


def score_eco_ethics(
    origin_country: Optional[str],
    category: str,
    certifications: Optional[List[str]] = None,
) -> EcoScore:
    certifications = certifications or []
    co2_factor = CO2_BY_COUNTRY.get(origin_country, 0.60)
    labor_factor = LABOR_BY_COUNTRY.get(origin_country, 55)
    base_co2 = CO2_BY_CATEGORY.get(category, 50.0)
    co2_estimate = base_co2 * (co2_factor / 0.45)

    if co2_estimate < base_co2 * 0.7:
        co2_score = 80
    elif co2_estimate < base_co2:
        co2_score = 65
    elif co2_estimate < base_co2 * 1.5:
        co2_score = 45
    else:
        co2_score = 25

    if any("エコ" in c or "green" in c.lower() for c in certifications):
        co2_score = min(100, co2_score + 10)

    overall = int(
        co2_score * 0.35 + labor_factor * 0.30 + 60 * 0.20 + 70 * 0.15
    )

    green_alt = None
    if origin_country != "JP" and category in CO2_BY_CATEGORY:
        saving_pct = int((1 - 0.45 / co2_factor) * 100)
        # 原産国が日本より低炭素 (co2_factor < 0.45、例: DE 0.30 / US 0.38) の場合 saving_pct <= 0。
        # 「国産代替で削減」は成立しない (むしろ増加) ため提案しない。負の削減率を表示するバグだった。
        if saving_pct > 0:
            green_alt = f"国産代替でCO2{saving_pct}%削減可"

    return EcoScore(
        overall=max(0, min(100, overall)),
        co2_score=max(0, min(100, co2_score)),
        labor_score=max(0, min(100, labor_factor)),
        co2_kg=co2_estimate,
        green_alternative=green_alt,
    )
