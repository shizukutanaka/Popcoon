"""proto_title_similarity のテスト (ProductMatcher 2-2 文字 2-gram Dice 併用)."""

import math

import pytest

from proto_title_similarity import (
    BIGRAM_DICE_WEIGHT,
    bigram_dice,
    normalize_for_bigrams,
    normalize_tokens,
    title_similarity,
    token_jaccard,
)

MATCH_THRESHOLD = 0.6  # Kotlin ProductMatcher.MATCH_THRESHOLD と一致


class TestBigramRescuesSpacelessTitles:
    """核心: 分かち書きの有無だけが違う同一商品を、Jaccard 退化から救済する."""

    def test_spaceless_vs_spaced_same_product_rescued(self):
        a = "明治おいしい牛乳900ml"
        b = "明治 おいしい牛乳 900ml 送料無料"
        # トークン Jaccard は退化 (片方が 1 巨大トークン、ノイズ除去後も一致なし)
        assert token_jaccard(a, b) == 0.0
        # 正規化後の 2-gram 文字列は完全一致 → dice = 1.0
        assert normalize_for_bigrams(a) == normalize_for_bigrams(b)
        assert bigram_dice(a, b) == pytest.approx(1.0)
        # ブレンドで閾値超え
        assert title_similarity(a, b) == pytest.approx(BIGRAM_DICE_WEIGHT)
        assert title_similarity(a, b) >= MATCH_THRESHOLD

    def test_spaceless_identical_titles(self):
        # 完全一致は単一トークンが一致するため Jaccard=1.0 で満点 (Dice 経路は使われない)
        s = "キリン午後の紅茶ミルクティー500ml"
        assert token_jaccard(s, s) == pytest.approx(1.0)
        assert title_similarity(s, s) == pytest.approx(1.0)


class TestBigramDoesNotOvermatch:
    """減衰係数 0.75 は、ブランド+カテゴリ語を共有するだけの別商品を弾く."""

    def test_earphone_vs_headphone_not_matched(self):
        c = "ソニー ワイヤレスイヤホン"
        d = "ソニー ワイヤレスヘッドホン"
        # raw dice は高い (共通接頭辞が長い) が、減衰後は閾値未満
        assert bigram_dice(c, d) > MATCH_THRESHOLD
        assert title_similarity(c, d) < MATCH_THRESHOLD

    def test_completely_different_products_zero(self):
        g = "コーヒー豆 ブラジル 500g"
        h = "ゲーミングマウス ロジクール"
        assert title_similarity(g, h) == 0.0


class TestJaccardStillDominatesWhereItWorks:
    """空白区切りが機能するタイトルでは、語順に頑健な Jaccard が支配する."""

    def test_word_reorder_full_match_via_jaccard(self):
        e = "ソニー WH-1000XM5 ブラック"
        f = "ブラック WH-1000XM5 ソニー"
        assert token_jaccard(e, f) == pytest.approx(1.0)
        # Dice は語順で下がるが、max() で Jaccard が勝つ
        assert title_similarity(e, f) == pytest.approx(1.0)

    def test_blend_is_max_of_components(self):
        a = "パナソニック 電動歯ブラシ ドルツ EW-DP55"
        b = "Panasonic ドルツ EW-DP55 電動歯ブラシ 白"
        expected = max(token_jaccard(a, b), BIGRAM_DICE_WEIGHT * bigram_dice(a, b))
        assert title_similarity(a, b) == pytest.approx(expected)


class TestProperties:
    def test_symmetric(self):
        a = "任天堂 Switch 有機ELモデル ホワイト"
        b = "Nintendo Switch(有機ELモデル) ホワイト 新品"
        assert title_similarity(a, b) == pytest.approx(title_similarity(b, a))

    def test_in_unit_range(self):
        pairs = [
            ("あ", "い"),
            ("", ""),
            ("A", ""),
            ("同じ商品", "同じ商品"),
            ("明治おいしい牛乳", "森永のおいしい牛乳"),
        ]
        for a, b in pairs:
            s = title_similarity(a, b)
            assert 0.0 <= s <= 1.0, f"{a!r},{b!r} -> {s}"

    def test_single_char_titles_no_bigram(self):
        # 1 文字は 2-gram を作れない → dice=0、トークンも 2 文字未満で除外 → 0
        assert bigram_dice("あ", "あ") == 0.0
        assert title_similarity("あ", "あ") == 0.0

    def test_empty_titles_zero(self):
        assert title_similarity("", "") == 0.0
        assert title_similarity("商品", "") == 0.0


class TestNormalizationConsistency:
    """トークン化と 2-gram 化が同じ前段正規化 (NFKC/ノイズ/記号) を共有する."""

    def test_fullwidth_and_noise_stripped_in_bigrams(self):
        # 全角英数 → 半角、ノイズ語除去は両経路で同じ
        a = "ＡＢＣ　ヘッドホン【送料無料】"
        b = "ABC ヘッドホン"
        assert normalize_for_bigrams(a) == normalize_for_bigrams(b)
        assert bigram_dice(a, b) == pytest.approx(1.0)

    def test_tokens_still_two_char_minimum(self):
        assert normalize_tokens("A BB CCC") == {"bb", "ccc"}
