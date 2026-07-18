"""proto_volume_attr のテスト (ProductMatcher 内容量/重量 不一致ペナルティ)."""

import pytest

from proto_volume_attr import extract_volume, volume_mismatch


class TestExtractLiquid:
    def test_ml(self):
        assert extract_volume("花王 アタック 洗剤 500ml") == ("ml", 500)

    def test_liter_uppercase_normalizes_to_ml(self):
        assert extract_volume("お茶 2L ペットボトル") == ("ml", 2000)

    def test_liter_kanji(self):
        assert extract_volume("洗剤 1リットル") == ("ml", 1000)

    def test_milliliter_kanji(self):
        assert extract_volume("化粧水 150ミリリットル") == ("ml", 150)

    def test_cc(self):
        assert extract_volume("エンジンオイル 200cc") == ("ml", 200)

    def test_decimal_liter(self):
        assert extract_volume("飲料 1.5L") == ("ml", 1500)


class TestExtractWeight:
    def test_grams(self):
        # base は mg。500g = 500,000mg。
        assert extract_volume("コーヒー豆 ブラジル 500g") == ("g", 500_000)

    def test_kg_normalizes_to_mg(self):
        assert extract_volume("プロテイン 1kg") == ("g", 1_000_000)

    def test_kg_equals_grams(self):
        assert extract_volume("プロテイン 1kg") == extract_volume("プロテイン 1000g")

    def test_mg_precision_preserved(self):
        # サプリの mg スケールを潰さない (500mg と 300mg が別 SKU として区別できる)
        assert extract_volume("サプリ 500mg") == ("g", 500)
        assert extract_volume("サプリ 300mg") == ("g", 300)


class TestFalsePositiveGuards:
    """この種の軽量ヒューリスティクスで最も危険な誤爆を防ぐ."""

    def test_network_generation_5g_not_weight(self):
        # 大文字 G はネットワーク世代。小文字 g のみ拾うので None。
        assert extract_volume("SIMフリー 5G スマホ") is None
        assert extract_volume("4G LTE ルーター") is None

    def test_milli_alone_is_length_not_volume(self):
        # 「5ミリ」は通常 5mm=長さ。ミリリットル全体でのみ拾う。
        assert extract_volume("ネジ 5ミリ 10本") is None

    def test_mah_not_weight(self):
        assert extract_volume("モバイルバッテリー 1000mAh") is None

    def test_no_volume(self):
        assert extract_volume("ソニー WH-1000XM5 ヘッドホン") is None

    def test_model_number_digits_not_volume(self):
        assert extract_volume("アイリスオーヤマ SB-2000 加湿フィルター") is None


class TestAmbiguityIsNeutral:
    def test_multiple_liquid_amounts_none(self):
        assert extract_volume("調味料セット 500ml×2本 計1L") is None

    def test_liquid_and_weight_both_none(self):
        # 両ドメインが出たらどちらを基準にすべきか不明 → 中立
        assert extract_volume("ドレッシング 200ml オイル 50g 配合") is None


class TestVolumeMismatch:
    def test_different_liquid_is_mismatch(self):
        assert volume_mismatch("洗剤 500ml", "洗剤 1L") is True

    def test_same_liquid_after_normalization_not_mismatch(self):
        assert volume_mismatch("洗剤 1L", "洗剤 1リットル") is False

    def test_kg_vs_g_same_amount_not_mismatch(self):
        assert volume_mismatch("プロテイン 1kg", "プロテイン 1000g") is False

    def test_different_weight_is_mismatch(self):
        assert volume_mismatch("コーヒー 200g", "コーヒー 500g") is True

    def test_cross_domain_not_mismatch(self):
        # ml と g は別ドメイン。偶然同じ数字でも不一致とみなさない (保守的)。
        assert volume_mismatch("X 500ml", "X 500g") is False

    def test_missing_side_not_mismatch(self):
        assert volume_mismatch("ヘッドホン", "ヘッドホン 500g") is False

    def test_symmetric(self):
        a, b = "洗剤 500ml", "洗剤 1L"
        assert volume_mismatch(a, b) == volume_mismatch(b, a)
