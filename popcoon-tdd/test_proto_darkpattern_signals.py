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


def test_scarcity_counter_units_beyond_ten():
    # 「点」以外の在庫助数詞 (個/セット/台) も同義の在庫煽りとして拾う
    assert _sev("残り3個", "SCARCITY") == "HIGH"
    assert _sev("あと2セット", "SCARCITY") == "HIGH"
    assert _sev("残り1台", "SCARCITY") == "HIGH"
    assert _sev("残り20個", "SCARCITY") == "MEDIUM"


def test_scarcity_ato_prefix():
    # 「あと」も「残り」と同義の接頭辞
    assert _sev("あと3点", "SCARCITY") == "HIGH"


def test_scarcity_evidence_preserves_matched_unit():
    for w in detect_dark_patterns("残り2セット"):
        if w["category"] == "SCARCITY":
            assert w["evidence"] == "残り2セット"


def test_time_counter_not_scarcity():
    # 「残り3時間」は URGENCY、在庫系助数詞ではないので SCARCITY にしない
    assert "SCARCITY" not in _cats("残り3時間")


def test_days_counter_not_scarcity():
    # 「あと5日で発送」の 日 は在庫助数詞ではない (誤検出しない)
    assert "SCARCITY" not in _cats("あと5日で発送")


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


def test_hidden_subscription_detected_high():
    # 消費者庁 2025-04 実態調査でも最頻出級。継続を強制/自動化する語で HIGH。
    for text in ["定期購入コースへ自動で切替", "ご注文は自動更新されます",
                 "This subscription automatically renews monthly",
                 "3回以上の継続が条件"]:
        out = detect_dark_patterns(text)
        assert any(w["category"] == "HIDDEN_SUBSCRIPTION" and w["severity"] == "HIGH"
                   for w in out), text


def test_hidden_subscription_no_false_positive_on_neutral_product():
    assert "HIDDEN_SUBSCRIPTION" not in _cats("高品質なワイヤレスイヤホン 送料無料")


def test_hidden_subscription_sort_order():
    # HIDDEN_SUBSCRIPTION は FORCED_ACTION と MISDIRECTION の間 (アルファベット順)。
    cats = [w["category"] for w in detect_dark_patterns("本日限り 定期購入コース")]
    assert cats == ["HIDDEN_SUBSCRIPTION", "URGENCY"]


# ── OBSTRUCTION (解約妨害 / OECD "Obstruction") ────────────────────────────


def test_obstruction_phone_only_is_high():
    # 解約手段を電話に限定する表現。相談事例で最も実害が大きい類型 → HIGH。
    for text in [
        "解約はお電話のみで承ります",
        "解約のご連絡はお電話に限ります",
        "退会は電話だけの受付です",
        "定期の停止はお電話のみ",
        "お電話のみでの解約受付となります",
        "Cancellation is accepted by phone only",
        "Call us to cancel your subscription",
    ]:
        assert _sev(text, "OBSTRUCTION") == "HIGH", text


def test_obstruction_deadline_is_medium():
    # 次回発送日起点の事前連絡期限 → 実効的な解約可能期間を圧縮する条件 (MEDIUM)。
    for text in [
        "解約は次回お届け予定日の10日前までにご連絡ください",
        "次回発送日の5日前までにお申し出ください",
        "次回の配送の7日前までに解約手続きが必要です",
        "You must cancel at least 10 days before the next shipment",
    ]:
        assert _sev(text, "OBSTRUCTION") == "MEDIUM", text


def test_obstruction_phone_only_outranks_deadline():
    # 両方該当するときは深刻な電話限定 (HIGH) を採用する。
    text = "解約は次回発送の10日前までにご連絡ください。お手続きは解約専用のお電話のみ"
    assert _sev(text, "OBSTRUCTION") == "HIGH"


def test_obstruction_gap_guard_is_sentence_local():
    # 解約と「電話のみ」が離れている (12文字超) 場合は限定の係り先が不明なので拾わない。
    # 「解約はマイページから可能ですがお問い合わせはお電話のみ」を誤検出しないための境界。
    assert "OBSTRUCTION" not in _cats("解約はマイページから可能ですがお問い合わせはお電話のみ")


def test_obstruction_no_false_positive_on_multiple_channels():
    # 「電話またはマイページから」= 手段が複数 → 妨害ではない (限定語が無い)。
    assert "OBSTRUCTION" not in _cats("解約は電話またはマイページからいつでも可能です")
    assert "OBSTRUCTION" not in _cats("解約はマイページからいつでも手続きできます")


def test_obstruction_no_false_positive_on_unrelated_phone_or_deadline():
    # 解約と無関係な電話案内・期限表記は拾わない。
    assert "OBSTRUCTION" not in _cats("お問い合わせはお電話のみ受け付けています")
    assert "OBSTRUCTION" not in _cats("次回お届け日の変更は3日前まで可能です")
    assert "OBSTRUCTION" not in _cats("高品質なワイヤレスイヤホン 送料無料")


def test_obstruction_is_independent_of_hidden_subscription():
    # 「定期購入」の語が無くても解約妨害だけで検出できる (別類型として独立)。
    out = detect_dark_patterns("解約はお電話のみで承ります")
    assert [w["category"] for w in out] == ["OBSTRUCTION"]


def test_obstruction_sort_order():
    # アルファベット順: MISDIRECTION < OBSTRUCTION < SCARCITY < URGENCY。
    text = "本日限り 残り2点 デフォルトでチェック 解約はお電話のみ"
    cats = [w["category"] for w in detect_dark_patterns(text)]
    assert cats == ["MISDIRECTION", "OBSTRUCTION", "SCARCITY", "URGENCY"]
    assert cats == sorted(cats)
