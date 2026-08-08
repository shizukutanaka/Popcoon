"""proto_title_similarity のテスト (ProductMatcher 2-2 文字 2-gram Dice 併用)."""

import math

import pytest

from proto_title_similarity import (
    BIGRAM_DICE_WEIGHT,
    bigram_dice,
    normalize_for_bigrams,
    normalize_tokens,
    title_similarity,
    token_idf_weights,
    token_jaccard,
    weighted_jaccard,
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


# ── IDF-lite トークン重み付け (研究 3-1, Sparkly PVLDB 2023) ────────────────

# 型番・容量・色・個数のいずれも取れない一般食品。既存の属性ペナルティが全て中立に
# なるため、タイトル類似度だけが名寄せの可否を決める領域。
_HONEY_CORPUS = [
    "山田養蜂場 国産 アカシア はちみつ",
    "山田養蜂場 国産 れんげ はちみつ",
    "山田養蜂場 国産 そば はちみつ",
    "杉養蜂園 国産 アカシア はちみつ",
]


def test_weights_none_is_identical_to_plain_jaccard():
    # 後方互換の要: weights を渡さない経路は既存の値と厳密一致する。
    pairs = [
        ("明治おいしい牛乳900ml", "明治 おいしい牛乳 900ml 送料無料"),
        ("ソニー ワイヤレスイヤホン", "ソニー ワイヤレスヘッドホン"),
        ("ソニー WH-1000XM5 ブラック", "ブラック WH-1000XM5 ソニー"),
        ("コーヒー豆 ブラジル 500g", "ゲーミングマウス ロジクール"),
        ("", ""),
    ]
    for a, b in pairs:
        assert weighted_jaccard(a, b, None) == token_jaccard(a, b), (a, b)
        assert title_similarity(a, b, None) == title_similarity(a, b), (a, b)


def test_uniform_corpus_weights_reduce_to_plain_jaccard():
    # 全トークンの df が等しい corpus なら IDF も一様 → 素の Jaccard と一致 (数学的性質)。
    # 比較する全トークンが corpus に含まれ df も等しいこと (未知語が混ざると
    # 既定重みが効いて一様でなくなる)。
    corpus = ["あか あお みどり", "きいろ しろ くろ"]
    w = token_idf_weights(corpus)
    assert len(set(w.weights.values())) == 1
    a, b = "あか あお みどり", "きいろ あお みどり"
    assert weighted_jaccard(a, b, w) == pytest.approx(token_jaccard(a, b))


def test_idf_demotes_pairs_sharing_only_generic_tokens():
    # 共有語 (山田養蜂場/国産/はちみつ) が候補集合で凡庸、相違語 (アカシア/れんげ) が
    # 識別的なペア。素の Jaccard はちょうど閾値 0.6 に達して誤マッチするが、
    # IDF 重み付けなら閾値を下回る。
    w = token_idf_weights(_HONEY_CORPUS)
    a, b = _HONEY_CORPUS[0], _HONEY_CORPUS[1]
    assert token_jaccard(a, b) >= 0.6
    assert weighted_jaccard(a, b, w) < 0.6


def test_idf_keeps_true_duplicates_high():
    # 同一商品の表記ゆれ (ノイズ語の有無) は IDF を掛けても高いまま。
    corpus = _HONEY_CORPUS + ["山田養蜂場 国産 アカシア はちみつ 送料無料"]
    w = token_idf_weights(corpus)
    a, b = "山田養蜂場 国産 アカシア はちみつ", "山田養蜂場 国産 アカシア はちみつ 送料無料"
    assert weighted_jaccard(a, b, w) == pytest.approx(1.0)  # 送料無料 はノイズ語で除去される
    assert title_similarity(a, b, w) >= 0.6


def test_idf_weight_ordering_common_token_is_smallest():
    w = token_idf_weights(_HONEY_CORPUS)
    # 全4件に出る 国産/はちみつ が最小、1件だけの れんげ/そば が最大。
    assert w.of("国産") == w.of("はちみつ") < w.of("山田養蜂場") < w.of("れんげ")
    assert w.of("れんげ") == w.of("そば")
    # corpus 外トークンは df=1 相当 = 最も稀なトークンと同じ重み
    assert w.of("corpusに無い語") == pytest.approx(w.of("れんげ"))


def test_unknown_token_treated_as_most_discriminative():
    # corpus 外のトークンは df=1 相当 = corpus 中で最も稀なトークンと同じ重み。
    # よって「未知語で相違するペア」と「df=1 の語で相違するペア」は同スコアになる。
    w = token_idf_weights(_HONEY_CORPUS)
    unknown = weighted_jaccard("国産 まったく未知の語", "国産 別の未知語", w)
    known = weighted_jaccard("国産 れんげ", "国産 そば", w)
    assert unknown == pytest.approx(known)
    # 凡庸な語だけで相違するペアより強く減点される (識別語の相違の方が重い)。
    generic = weighted_jaccard("れんげ 国産", "れんげ はちみつ", w)
    assert unknown < generic


def test_empty_corpus_returns_none():
    assert token_idf_weights([]) is None
    # None は素の Jaccard へフォールバック
    assert title_similarity("あか あお", "あか みどり", token_idf_weights([])) == \
        title_similarity("あか あお", "あか みどり")


def test_weighted_is_deterministic():
    w = token_idf_weights(_HONEY_CORPUS)
    a, b = _HONEY_CORPUS[0], _HONEY_CORPUS[1]
    assert weighted_jaccard(a, b, w) == weighted_jaccard(a, b, w)


def test_bigram_arm_still_rescues_spaceless_titles_under_weighting():
    # 分かち書きなしタイトルはトークンが 1 個に退化するが、Dice 側の腕が効くので
    # IDF を入れても救済される (max ブレンドの設計が壊れていないことの確認)。
    corpus = ["明治おいしい牛乳900ml", "明治 おいしい牛乳 900ml"]
    w = token_idf_weights(corpus)
    assert title_similarity("明治おいしい牛乳900ml", "明治 おいしい牛乳 900ml", w) >= 0.6
