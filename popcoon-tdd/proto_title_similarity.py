"""商品タイトル類似度 — 文字 2-gram Dice 併用ブレンド (ProductMatcher 2-2 のオラクル).

日本語 EC タイトルは分かち書きが無いことが多く、トークン集合の Jaccard 類似度は
「タイトル全体が 1 トークン」に退化する (同一商品でも類似度 0)。文字 2-gram の
Dice 係数は空白に依存しないためこれを補える。一方で 2-gram Dice は
「ブランド+カテゴリ語を共有するだけの別商品」(イヤホン vs ヘッドホン 等) を
系統的に高く見積もるため、0.75 の減衰係数を掛けてからトークン Jaccard と
max() でブレンドする:

    title_similarity = max(token_jaccard, 0.75 * bigram_dice)

- 空白区切りが機能するタイトル同士 → 従来どおり Jaccard が支配 (語順にも頑健)
- 分かち書きなしタイトル → Dice が救済 (同一内容なら 0.75*1.0 = 0.75 >= 閾値 0.6)
- 別カテゴリ商品 (イヤホン vs ヘッドホン, raw dice ≈ 0.73) → ×0.75 ≈ 0.545 < 0.6

Kotlin 実装 (app/.../feature/matching/ProductMatcher.kt) と正規化パイプライン・
定数を厳密に一致させること。kotlin_parity/run_matcher.sh が実 Kotlin 実行で照合する。
"""

from __future__ import annotations

import math
import re
import unicodedata
from typing import NamedTuple

# Kotlin ProductMatcher の各 Regex と一致させる (文字集合・順序とも)
NOISE_RE = re.compile(
    r"送料無料|正規品|新品|未使用|即日発送|あす楽|ポイント\d*倍|"
    r"公式|国内正規|メーカー保証|限定|セール|お買い得|人気|おすすめ"
)
SYMBOL_RE = re.compile(r"[\[\]【】（）()「」『』、。,.!！?？/／・:：;；\"'`~〜\-_=+*#@&|]")
WHITESPACE_RE = re.compile(r"\s+")

# 2-gram Dice の減衰係数 (Kotlin: BIGRAM_DICE_WEIGHT)
BIGRAM_DICE_WEIGHT = 0.75


def _normalize_base(title: str) -> str:
    """NFKC → 小文字化 → ノイズ語/記号を空白に置換 (トークン/2-gram 共通の前段)."""
    s = unicodedata.normalize("NFKC", title).lower()
    s = NOISE_RE.sub(" ", s)
    s = SYMBOL_RE.sub(" ", s)
    return s


def normalize_tokens(title: str) -> set[str]:
    """Kotlin normalizeTitle(): 空白分割し 2 文字以上のトークン集合."""
    return {t for t in WHITESPACE_RE.split(_normalize_base(title)) if len(t) >= 2}


def normalize_for_bigrams(title: str) -> str:
    """2-gram 用: 前段正規化から空白を全除去した 1 本の文字列."""
    return WHITESPACE_RE.sub("", _normalize_base(title))


def char_bigrams(s: str) -> set[str]:
    return {s[i : i + 2] for i in range(len(s) - 1)}


def bigram_dice(title_a: str, title_b: str) -> float:
    """正規化済みタイトルの文字 2-gram 集合 Dice 係数 (0.0-1.0)。2 文字未満は 0."""
    a = char_bigrams(normalize_for_bigrams(title_a))
    b = char_bigrams(normalize_for_bigrams(title_b))
    if not a or not b:
        return 0.0
    return 2.0 * len(a & b) / (len(a) + len(b))


def token_jaccard(title_a: str, title_b: str) -> float:
    a = normalize_tokens(title_a)
    b = normalize_tokens(title_b)
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


class TokenWeights(NamedTuple):
    """トークン → IDF 重みの表と、corpus 外トークンに使う既定重み。

    既定重みは df=1 相当 (= corpus で最も稀) の `ln(1 + N)`。`weights` の要素数ではなく
    **文書数 N** から決まる点が重要 (語彙数を使うと corpus の語彙が増えるだけで
    未知語の重みが動いてしまう)。
    """

    weights: dict[str, float]
    default: float

    def of(self, token: str) -> float:
        return self.weights.get(token, self.default)


def token_idf_weights(corpus_titles: list[str]) -> TokenWeights | None:
    """候補集合を corpus とみなしたトークン IDF 重み表 (研究 3-1)。

    出典: Sparkly — A Simple yet Surprisingly Strong TF/IDF Blocker for Entity Matching
    (Paulsen, Govind, Doan — PVLDB vol.16, 2023)。エンティティ解決のブロッキングに
    tf/idf を素直に使うと state-of-the-art な 8 手法を上回る、という結果に対応する。

    重みは平滑化つき `idf(t) = ln(1 + N / df(t))`:
      - 候補集合の全件に出るトークン (ブランド名・カテゴリ語) → ln(2) ≈ 0.69 で最小
      - 1 件にしか出ないトークン (花の種類・シリーズ名等の識別語) → ln(1+N) で最大
    df=0 (corpus 外のトークン) は df=1 として扱い、未知語を最も識別的とみなす。

    corpus が空なら None を返す (= 重み無し = 素の Jaccard へフォールバック)。
    """
    if not corpus_titles:
        return None
    docs = [normalize_tokens(t) for t in corpus_titles]
    n = len(docs)
    df: dict[str, int] = {}
    for d in docs:
        for tok in d:
            df[tok] = df.get(tok, 0) + 1
    return TokenWeights(
        weights={tok: math.log(1.0 + n / c) for tok, c in df.items()},
        default=math.log(1.0 + n),
    )


def weighted_jaccard(
    title_a: str, title_b: str, weights: TokenWeights | None
) -> float:
    """IDF 重み付き Jaccard。`Σ_{t∈A∩B} w(t) / Σ_{t∈A∪B} w(t)`。

    weights=None なら素の [token_jaccard] に **完全に委譲** する。全 w(t) が等しい場合は
    数学的に素の Jaccard と一致するが、浮動小数の丸めで最下位ビットがずれ得るため
    委譲で厳密一致を保証する (既存のゴールデン/parity を無回帰にするための設計)。
    """
    if weights is None:
        return token_jaccard(title_a, title_b)
    a = normalize_tokens(title_a)
    b = normalize_tokens(title_b)
    if not a or not b:
        return 0.0
    union = sum(weights.of(t) for t in a | b)
    if union <= 0.0:
        return 0.0
    return sum(weights.of(t) for t in a & b) / union


def title_similarity(
    title_a: str, title_b: str, weights: TokenWeights | None = None
) -> float:
    """ブレンド済みタイトル類似度 (Kotlin ProductMatcher.similarity() の titleSim 相当).

    weights を渡すとトークン側が IDF 重み付き Jaccard になる。2-gram Dice の腕は
    そのまま残るため、識別語が 1 つだけ違う「同一商品の表記ゆれ」は Dice 側で救済され、
    IDF による減点は「共有語が凡庸で相違語が識別的」なペアに集中する。
    """
    return max(
        weighted_jaccard(title_a, title_b, weights),
        BIGRAM_DICE_WEIGHT * bigram_dice(title_a, title_b),
    )
