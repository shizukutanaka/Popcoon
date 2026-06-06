"""Tests for proto_darkpattern_signals (ダークパターン暴露 検証)."""

from proto_darkpattern_signals import detect_dark_patterns


def _cats(text, stock_count=None):
    return {w["category"] for w in detect_dark_patterns(text, stock_count)}


def _sev(text, category, stock_count=None):
    for w in detect_dark_patterns(text, stock_count):
        if w["category"] == category:
            return w["severity"]
    return None


def test_clean_text_no_warnings():
    assert detect_dark_patterns("オーガニックコットン100%のタオルです。送料無料。") == []


def test_urgency_countdown():
    assert "URGENCY" in _cats("セール残り2時間で終了")


def test_urgency_today_only():
    assert "URGENCY" in _cats("本日限りの特別価格")


def test_scarcity_low_number_is_high():
    assert _sev("在庫: 残り2点", "SCARCITY") == "HIGH"


def test_scarcity_large_number_is_medium():
    assert _sev("残り50点", "SCARCITY") == "MEDIUM"


def test_scarcity_text_vague_is_high():
    assert _sev("在庫わずか！お早めに", "SCARCITY") == "HIGH"


def test_scarcity_from_stock_count():
    assert _sev("通常の商品説明", "SCARCITY", stock_count=1) == "HIGH"


def test_no_scarcity_when_stock_ample():
    assert "SCARCITY" not in _cats("通常の商品説明", stock_count=50)


def test_social_proof():
    assert "SOCIAL_PROOF" in _cats("いま12人がこの商品を見ています")


def test_misdirection_precheck():
    assert "MISDIRECTION" in _cats("延長保証はデフォルトで選択されています")


def test_confirmshaming_is_high():
    w = detect_dark_patterns("いいえ、割引はいりません")
    assert any(x["category"] == "FORCED_ACTION" and x["severity"] == "HIGH" for x in w)


def test_english_patterns():
    cats = _cats("Only 1 left, hurry! 5 people are viewing this")
    assert "SCARCITY" in cats
    assert "URGENCY" in cats
    assert "SOCIAL_PROOF" in cats


def test_multiple_categories_detected():
    text = "本日限り！残り3点。8人がカートに入れました"
    assert _cats(text) == {"URGENCY", "SCARCITY", "SOCIAL_PROOF"}


def test_output_sorted_and_one_per_category():
    text = "本日限り 今だけ 残り1点 在庫わずか"
    out = detect_dark_patterns(text)
    cats = [w["category"] for w in out]
    assert cats == sorted(cats)          # category 昇順
    assert len(cats) == len(set(cats))   # 各カテゴリ最大1件


def test_deterministic():
    text = "本日限り！残り3点。8人がカートに入れました"
    assert detect_dark_patterns(text) == detect_dark_patterns(text)
