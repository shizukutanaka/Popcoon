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

import re
import unicodedata

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


def title_similarity(title_a: str, title_b: str) -> float:
    """ブレンド済みタイトル類似度 (Kotlin ProductMatcher.similarity() の titleSim 相当)."""
    return max(token_jaccard(title_a, title_b), BIGRAM_DICE_WEIGHT * bigram_dice(title_a, title_b))
