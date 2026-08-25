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
    """Holt's linear smoothing による価格予測

    real_price <= 0 のレコードは統計に入れない。取得失敗を 0 円として記録してしまった
    汚染レコードであり、実際に成立した価格ではない (BuyTimingScorer と同じクラスの欠陥)。
    混ぜたときの実測被害 (30 点、うち 3 点が ¥0 のボラタイルな系列):
      - historic_low が 3000 → **0** (UI に「過去最安 ¥0」と表示される)
      - IQR の四分位が下へ引きずられ、本物の高値が外れ値として捨てられて
        historic_high が 12000 → 8000、predicted_7d が 5281 → 4121 (-22%)
      - 末尾 1 件だけが ¥0 の系列では current_price=0 かつ
        percentile=1.0 となり buy_now_probability が 0.167 → **0.5** (3 倍) に跳ねる
    有効レコードが 14 件に満たなければ従来どおり None (母数は「有効な観測数」)。
    """
    valid = [r for r in records if r.real_price > 0]
    if len(valid) < 14:
        return None

    data = [float(r.real_price) for r in valid]
    cleaned = _remove_outliers_iqr(data)
    if len(cleaned) < 2:
        return None

    level, trend = _holt_linear(cleaned, alpha=0.3, beta=0.1)
    # 7 日先のみ 3 手法の中央値アンサンブル (研究 B1)。30 日先は Holt 単独のまま —
    # アンサンブル化すると点予測の MAE は改善するが、予測区間を較正できなくなる
    # (下記 ensemble_forecast の docstring とコミットメッセージに実測を記載)。
    pred_7 = max(0, int(ensemble_forecast(cleaned, 7)))
    pred_30 = max(0, int(level + trend * 30))

    current = valid[-1].real_price
    low = min(cleaned)
    high = max(cleaned)

    # 買い時確率: percentile + trend
    pct = sum(1 for p in cleaned if p >= current) / len(cleaned)
    trend_boost = 0.3 if trend < 0 else 0.0
    buy_prob = min(1.0, max(0.0, pct * 0.5 + trend_boost))

    # 信頼度も「有効な観測数」で決める。汚染レコードを頭数に入れると、
    # 実データが少ないのに HIGH と表示してしまう。
    conf = (
        Confidence.HIGH if len(valid) >= 90
        else Confidence.MEDIUM if len(valid) >= 30
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


def _holt_linear(
    data: List[float], alpha: float, beta: float, phi: float = 1.0
) -> Tuple[float, float]:
    """Holt 線形平滑 (phi=1.0) / damped-trend 平滑 (phi<1)。

    phi=1.0 のとき更新則は従来の Holt と **厳密に一致** する (後方互換)。
    damped (Gardner & McKenzie 1985): L = α·y + (1−α)(L + φT), T = β(L−prev) + (1−β)φT。
    """
    L = data[0]
    T = data[1] - data[0] if len(data) >= 2 else 0.0
    for y in data[1:]:
        prev_L = L
        L = alpha * y + (1 - alpha) * (L + phi * T)
        T = beta * (L - prev_L) + (1 - beta) * phi * T
    return L, T


# damped-trend の減衰係数。fpp3 (Hyndman & Athanasopoulos) が示す実用域 [0.8, 0.98] の
# 標準値。決定性を保つため推定せず固定する (2026-08 リサーチ B1)。
DAMPED_PHI = 0.9
# seasonal-naive の周期。日本 EC の価格は週次サイクル (週末セール等) が支配的で、
# SeasonalDecompForecast / SeasonalDowSignal と同じ既定に揃える。
ENSEMBLE_SEASON_PERIOD = 7


def ensemble_forecast(cleaned: List[float], horizon: int) -> float:
    """Holt / damped-trend Holt / seasonal-naive の 3 予測の **中央値** (研究 B1)。

    単独最良の手法はレジーム依存 (トレンド継続なら Holt、転換なら damped、週次季節性なら
    seasonal-naive) だが、中央値は **どのレジームでも最悪にならない**。これが採用理由。
    実測 MAE (合成価格系列 300 試行 × 4 レジーム、h=7): Holt 単独 123.0〜258.1 に対し
    中央値 120.7〜212.1 (-2〜-18%)。

    出典: damped trend が M3/M4 で複雑手法に対し一貫して競争的
    (Gardner & McKenzie 1985 / fpp3 §8.2)。統計アンサンブルが foundation model を
    上回るという Nixtla SCUM の知見は docs/RESEARCH-2026-07.md §2 に記録済み。

    **適用は h=7 のみ** (predict_price 参照)。h=30 では MAE の改善幅がさらに大きい
    (412.8→269.1 等、-21〜-35%) 一方で **予測区間を較正できなくなる**: 学習 90 点から
    得られる 30 ステップ先残差は約 60 本だが窓が重なるため実質独立なブロックは 2 個ほどで、
    アンサンブルの残差分位点は本番誤差を過小評価する。実測被覆率 (目標 90%) は
    適応追跡 78.0〜84.0% / 静的 split 79.8〜84.8% と **どちらも目標割れ**した
    (h=7 は 89.8〜91.5% で合格)。被覆保証は本アプリが明示している契約なので、
    較正できない予測器は採用しない。履歴が数百点得られるようになれば再検討する。

    - Holt: level + trend·h (従来の predicted_7d / predicted_30d と同一の腕)
    - damped: level_d + trend_d · Σ_{i=1..h} φ^i (φ = DAMPED_PHI)
    - seasonal-naive: cleaned[-period + ((h−1) mod period)]。
      len(cleaned) < period なら cleaned[-1] (= naive) にフォールバック

    cleaned は predict_price と同じ IQR 外れ値除去済み系列 (len >= 2) を想定。
    """
    if horizon < 1:
        raise ValueError("horizon must be >= 1")

    L, T = _holt_linear(cleaned, alpha=0.3, beta=0.1)
    holt_fc = L + T * horizon

    Ld, Td = _holt_linear(cleaned, alpha=0.3, beta=0.1, phi=DAMPED_PHI)
    damp_sum = sum(DAMPED_PHI ** i for i in range(1, horizon + 1))
    damped_fc = Ld + Td * damp_sum

    if len(cleaned) >= ENSEMBLE_SEASON_PERIOD:
        snaive_fc = cleaned[
            -ENSEMBLE_SEASON_PERIOD + ((horizon - 1) % ENSEMBLE_SEASON_PERIOD)
        ]
    else:
        snaive_fc = cleaned[-1]

    return sorted([holt_fc, damped_fc, snaive_fc])[1]


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


# 「本体」ではなく本体の付属品・消耗品・工事を指す語。TCO は本体前提のモデル
# (電力・消耗品が購入価格と独立した実額) なので、付属品に適用すると誤差が桁で出る。
# 例: 「エアコン洗浄スプレー ¥980」に air_conditioner を当てると電力だけで
# 5 年 275,940 円 = 本体価格の 283 倍という TCO が表示される。
_TCO_ACCESSORY_MARKERS = (
    "ケース", "カバー", "フィルム", "保護", "スタンド", "ホルダー", "ストラップ",
    "リング", "クリーナー", "洗浄", "脱臭", "消臭", "スプレー", "マット",
    "収納", "ラック", "フィルター", "詰め替え", "交換用", "互換",
    "カートリッジ", "リモコン", "工事", "ケーブル", "充電器", "アダプタ", "用紙",
)

# 「プリンター」を含むが消耗品体系が全く違う製品。インクジェット扱いすると
# インク代 (5 年 106,000 円) を無関係な製品に加算する。
_TCO_NON_INKJET_PRINTER = ("3d", "ラベル", "レシート", "感熱", "シール")

# スマートフォン語を含むが本体ではない製品 (タブレット・TV 端末・ウォッチ等)。
_TCO_NON_PHONE = ("タブレット", "tablet", "ipad", "ウォッチ", "watch", "tv", "ナビ")


def infer_tco_category(title: str) -> Optional[str]:
    """商品タイトルから TCO 対象カテゴリを推定する (該当なしは None)。

    設計方針: **取りこぼし (None) より誤検出のほうが有害**。
    TCO の電力・消耗品は購入価格と独立した実額なので、付属品や別ジャンルに
    誤適用すると「¥1,500 のサプリに 5 年 147,650 円」のような表示になる。
    逆に取りこぼした場合は TCO パネルが出ないだけで害がない。
    したがって曖昧な語 (単独の「カプセル」等) は積極的に捨てる。
    """
    t = title.lower()
    if any(m in t for m in _TCO_ACCESSORY_MARKERS):
        return None
    if "プリンター" in t and any(m in t for m in _TCO_NON_INKJET_PRINTER):
        return None
    if "レーザープリンター" in t or "レーザー複合機" in t:
        return "laser_printer"
    if "インクジェット" in t or ("プリンター" in t and "レーザー" not in t):
        return "inkjet_printer"
    if any(m in t for m in ("スマホ", "スマートフォン", "iphone", "android", "携帯電話")):
        return None if any(m in t for m in _TCO_NON_PHONE) else "smartphone"
    if any(m in t for m in ("ノートpc", "ノートパソコン", "laptop")):
        return "laptop"
    if "冷蔵庫" in t or "refrigerator" in t:
        return "refrigerator"
    # カーエアコンは車載 (電力モデルが家庭用と別) なので対象外。
    if ("エアコン" in t and "カーエアコン" not in t) or "air conditioner" in t:
        return "air_conditioner"
    # 単独の「カプセル」はカプセルトイ・サプリ・洗剤ジェルボールを巻き込むため使わない。
    # 本体は日本の EC では概ね「〜メーカー」「〜マシン」と表記される。
    if any(m in t for m in ("コーヒーメーカー", "コーヒーマシン", "エスプレッソマシン", "カプセルマシン")):
        return "coffee_capsule"
    return None


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
    """価格履歴からダークパターンを検出する。

    real_price <= 0 のレコードは統計に入れない。取得失敗を 0 円として記録した
    汚染レコードであり、実際に成立した価格ではない。混ぜると **販売者に対する
    冤罪** を作る:
      - 直近 7 日 / その前 7 日の平均を比べる「セール前値上げ」は、前半の窓に
        ¥0 が 1 件混ざるだけで prev_avg が下がり、価格が完全に平坦な商品にも
        PRE_SALE_MARKUP が付く (実測: 14 日すべて 10000 円の履歴で発火)
      - 「常設セール」は ¥0 が必ず list_price 未満に数えられ below 率を押し上げる
      - 「参考価格誇張」は履歴が全て ¥0 だと actual_high=0 になり必ず発火する
    ダークパターン検出は販売者を名指しする機能なので、誤検出は最も避けたい。
    母数 (30 件 / 14 件) も有効な観測数で数える。
    """
    warnings = []
    valid = [r for r in history if r.real_price > 0]

    # 1. 常設セール (90%以上の期間割引中)
    if list_price and list_price > current_price and len(valid) >= 30:
        below = sum(1 for r in valid if r.real_price < list_price)
        if below / len(valid) > 0.90:
            warnings.append(PsychWarning(
                WarningType.ALWAYS_ON_DISCOUNT, "常設セール", Severity.HIGH))

    # 2. 参考価格詐欺
    if list_price and valid:
        actual_high = max(r.real_price for r in valid)
        if list_price > actual_high * 1.5:
            warnings.append(PsychWarning(
                WarningType.INFLATED_LIST_PRICE, "参考価格誇張", Severity.HIGH))

    # 3. セール前値上げ
    if len(valid) >= 14:
        recent = valid[-7:]
        prev = valid[-14:-7]
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
