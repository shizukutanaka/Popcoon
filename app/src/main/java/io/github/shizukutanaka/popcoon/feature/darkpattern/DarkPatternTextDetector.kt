package io.github.shizukutanaka.popcoon.feature.darkpattern

/**
 * UIテキスト系ダークパターン検出
 * （URGENCY / SCARCITY / SOCIAL_PROOF / MISDIRECTION / FORCED_ACTION / HIDDEN_SUBSCRIPTION /
 * OBSTRUCTION）。
 * 価格・数値系は既存 DarkPatternDetector が担当。
 *
 * 学術根拠: Mathur CSCW2019 / AidUI ICSE'23 / arXiv:2211.06543。
 * Amazon FTC 和解（$2.5B）のIliad Flow などの事例を踏まえたルール設計。
 * HIDDEN_SUBSCRIPTION (隠れ定期購入) は消費者庁 2025-04 実態調査でも最頻出級の類型で、
 * 特商法 2027 改正で解約妨害の明文禁止が検討されている領域 (2026-07 リサーチ)。
 * オンデバイス処理・送信なし（I5 方針）。
 *
 * Python 参照 (popcoon-tdd/proto_darkpattern_signals.py) と完全一致。
 * パリティは DarkPatternTextDetectorTest（ゴールデンベクタ）で保証（PORTING_SPEC.md #5）。
 */
object DarkPatternTextDetector {

    /** カテゴリを Python 名（＝アルファベット順）で定義。ordinal がソートキーになる。 */
    enum class Category {
        FORCED_ACTION, HIDDEN_SUBSCRIPTION, MISDIRECTION, OBSTRUCTION, SCARCITY, SOCIAL_PROOF, URGENCY,
    }

    enum class Severity { LOW, MEDIUM, HIGH }

    data class Signal(
        val category: Category,
        val evidence: String,
        val severity: Severity,
    )

    // ── パターン定義（Python proto と 1:1 対応） ────────────────────────────

    // (?U) = UNICODE_CHARACTER_CLASS。\d \s \b を Unicode 対応にし、全角数字 (３) や
    // 全角空白 (U+3000) を Python (str 既定で Unicode) と同様に検出する。ASCII 専用の
    // Java/Kotlin 既定だと「残り３点」「残り　3　点」を取りこぼし、Python 参照と乖離する。
    // 緊急性の煽り。消費者庁 2026-06-18 意識調査で「過去1年に経験した」類型の **最多** が
    // 緊急性の強調 (76.2% が目撃、37.5% が経験) — recall を優先的に広げる価値が高い。
    // ただし正当な販促表示との重なりが大きい語は意図的に **入れない** (2026-08 リサーチ):
    //  - 「期間限定」: 「期間限定フレーバー」のように商品属性を指す用法が多く誤爆源。
    //  - 裸の「最終日」: 「最終日までにお届け」等の配送文脈を拾う。本日/セール/販売で限定。
    //  - 日単位のカウンタ: 「あと5日で発送」は納期であって煽りではない (時間/分/秒に限定)。
    private val URGENCY = listOf(
        // 「あと」は「残り」と同義の接頭辞。従来は 残り のみで「あと3時間」を取りこぼして
        // いた (SCARCITY 側は在庫助数詞で あと 対応済みだが URGENCY 側は未対応だった)。
        Regex("(?U)(?:残り|あと)\\s*\\d+\\s*(?:時間|分|秒)"),
        Regex("本日限り"),
        Regex("今だけ"),
        Regex("まもなく(?:終了|締切)"),
        Regex("(?:終了|締切|締め切り)間近"),
        Regex("売り切れ次第終了"),
        Regex("(?:本日|セール|販売)最終日"),
        Regex("ending soon", RegexOption.IGNORE_CASE),
        Regex("limited[- ]time", RegexOption.IGNORE_CASE),
        Regex("(?U)\\bhurry\\b", RegexOption.IGNORE_CASE),
        Regex("act now", RegexOption.IGNORE_CASE),
        Regex("today only", RegexOption.IGNORE_CASE),
        Regex("last chance", RegexOption.IGNORE_CASE),
        Regex("don'?t miss out", RegexOption.IGNORE_CASE),
        Regex("offer ends", RegexOption.IGNORE_CASE),
        Regex("final hours?", RegexOption.IGNORE_CASE),
        Regex("while supplies last", RegexOption.IGNORE_CASE),
    )

    private val SOCIAL_PROOF = listOf(
        Regex("(?U)\\d+\\s*人が[^。\\n]{0,15}(?:見て|閲覧|カート|購入)"),
        Regex("(?U)\\d+\\s+people are (?:viewing|looking)", RegexOption.IGNORE_CASE),
        Regex("(?U)in\\s+\\d+\\s+carts", RegexOption.IGNORE_CASE),
    )

    private val MISDIRECTION = listOf(
        Regex("(?:デフォルト|既定|初期設定)で(?:チェック|選択|追加)"),
        Regex("pre-?(?:checked|selected)", RegexOption.IGNORE_CASE),
    )

    private val CONFIRMSHAMING = listOf(
        Regex("いいえ.*(?:節約|お得|割引).*(?:したくない|不要|結構|いりません)"),
        Regex("(?U)no,?\\s+i\\s+(?:don't|do not)\\s+want\\s+to\\s+save", RegexOption.IGNORE_CASE),
    )

    // 隠れ定期購入 (subscription trap): 一見単発購入に見えて実は継続課金/最低回数縛り。
    // 消費者庁調査でも最頻出、FTC Amazon Prime 和解の中核類型。誤検出を避けるため
    // 「継続を強制/自動化する」語に限定 (単なる「定期便あり」の中立表記は拾わない)。
    private val HIDDEN_SUBSCRIPTION = listOf(
        Regex("定期(?:購入|便|コース|縛り)"),
        Regex("(?U)\\d+\\s*回(?:以上)?[^。\\n]{0,6}(?:継続|受け取り|購入)が(?:条件|必須|必要)"),
        Regex("自動(?:更新|継続|課金)"),
        Regex("(?U)auto[-\\s]?renew(?:s|al|ing)?", RegexOption.IGNORE_CASE),
        Regex("(?U)automatically\\s+renews?", RegexOption.IGNORE_CASE),
        Regex("(?U)recurring\\s+(?:billing|charge|payment|subscription)", RegexOption.IGNORE_CASE),
    )

    // 解約妨害 (OECD 2022 taxonomy の "Obstruction" / いわゆるローチモーテル)。
    // HIDDEN_SUBSCRIPTION が「契約が継続することを隠す」類型なのに対し、こちらは
    // 「契約後に抜けにくくする」類型で規制上も別物 (2026-08 リサーチ):
    // 消費者庁「デジタル取引・特定商取引法等検討会」第4回 (2026-04) が「契約・解約場面に
    // おける規律の在り方」を独立論点として審議 (中間とりまとめ 2026 夏)。特商法 2022-06 施行の
    // 最終確認画面義務の下でも「いつでも解約可能」と表示しつつ実際は「次回発送の N 日前までに
    // 電話で連絡」を課す相談が継続している。
    //
    // 深刻度2段階: 電話限定 = HIGH (「電話が繋がらない」が相談事例の最頻出の実害)、
    // 次回発送日起点の事前連絡期限 = MEDIUM (実効的な解約可能期間を圧縮する条件)。
    // 誤爆ガード: 限定語 (のみ/だけ/に限) を必須にし複数手段の提示は拾わない。期限側は
    // 後続に解約文脈語を要求し「次回お届け日の変更は3日前まで」を除外する。
    private val OBSTRUCTION_PHONE_ONLY = listOf(
        Regex("(?:解約|退会|定期[^。\\n]{0,4}(?:停止|解除))[^。\\n]{0,12}(?:お)?電話[^。\\n]{0,6}(?:のみ|だけ|に限)"),
        Regex("(?:お)?電話[^。\\n]{0,6}(?:のみ|だけ)[^。\\n]{0,12}(?:解約|退会)"),
        Regex("(?U)cancel(?:lation)?[^.\\n]{0,20}by\\s+phone\\s+only", RegexOption.IGNORE_CASE),
        Regex("(?U)call\\s+(?:us\\s+)?to\\s+cancel", RegexOption.IGNORE_CASE),
    )

    private val OBSTRUCTION_DEADLINE = listOf(
        Regex(
            "(?U)次回[^。\\n]{0,10}(?:発送|お届け|配送)[^。\\n]{0,10}\\d+\\s*日前まで" +
                "[^。\\n]{0,12}(?:解約|退会|停止|キャンセル|連絡|申し出|申出)",
        ),
        Regex("(?U)cancel[^.\\n]{0,40}\\d+\\s*days?\\s+before", RegexOption.IGNORE_CASE),
    )

    // ── 公開 API ────────────────────────────────────────────────────────────

    /**
     * UIテキスト中のダークパターン警告リストを返す（category 昇順、各カテゴリ最大1件）。
     *
     * @param text 商品タイトル・スニペット等の可視テキスト
     * @param stockCount 実際の在庫数（APIから取得可能な場合）
     */
    fun detect(text: String, stockCount: Int? = null): List<Signal> {
        // 空テキストでも stockCount による在庫切迫は検出する (Python 参照は早期 return しない)。
        // 旧実装は text.isBlank() で打ち切り、可視テキスト無し+低在庫の SCARCITY を取りこぼしていた。
        val warnings = mutableListOf<Signal>()

        firstMatch(text, URGENCY)?.let { ev ->
            warnings += Signal(Category.URGENCY, ev, Severity.MEDIUM)
        }

        detectScarcity(text, stockCount)?.let { warnings += it }

        firstMatch(text, SOCIAL_PROOF)?.let { ev ->
            warnings += Signal(Category.SOCIAL_PROOF, ev, Severity.MEDIUM)
        }

        firstMatch(text, MISDIRECTION)?.let { ev ->
            warnings += Signal(Category.MISDIRECTION, ev, Severity.MEDIUM)
        }

        firstMatch(text, CONFIRMSHAMING)?.let { ev ->
            warnings += Signal(Category.FORCED_ACTION, ev, Severity.HIGH)
        }

        firstMatch(text, HIDDEN_SUBSCRIPTION)?.let { ev ->
            warnings += Signal(Category.HIDDEN_SUBSCRIPTION, ev, Severity.HIGH)
        }

        detectObstruction(text)?.let { warnings += it }

        return warnings.sortedBy { it.category }
    }

    // ── プライベートヘルパー ─────────────────────────────────────────────────

    private fun firstMatch(text: String, patterns: List<Regex>): String? {
        for (p in patterns) p.find(text)?.let { return it.value }
        return null
    }

    /** 解約妨害。電話限定 (HIGH) を事前連絡期限 (MEDIUM) より優先する。 */
    private fun detectObstruction(text: String): Signal? {
        firstMatch(text, OBSTRUCTION_PHONE_ONLY)?.let { ev ->
            return Signal(Category.OBSTRUCTION, ev, Severity.HIGH)
        }
        firstMatch(text, OBSTRUCTION_DEADLINE)?.let { ev ->
            return Signal(Category.OBSTRUCTION, ev, Severity.MEDIUM)
        }
        return null
    }

    // 数字 → Int は kotlin の toInt() で十分: Character.digit ベースで全角数字 (３) も解釈する
    // (Python int() と同等)。取りこぼしの真因は regex の \d が ASCII 専用だった点で、(?U) で解消済み。
    private fun detectScarcity(text: String, stockCount: Int?): Signal? {
        // 在庫カウンタ: 「残り/あと N 点/個/セット/台」。点以外の在庫助数詞も法的に
        // 等価な在庫煽り。時間系 (残り3時間) は URGENCY 側なので助数詞を在庫系に限定。
        // Python proto (_detect_scarcity) と厳密一致。
        Regex("(?U)(?:残り|あと)\\s*(\\d+)\\s*(?:点|個|セット|台)").find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            return Signal(
                Category.SCARCITY, m.value,
                if (n <= 3) Severity.HIGH else Severity.MEDIUM,
            )
        }
        Regex("在庫わずか|残りわずか").find(text)?.let { m ->
            return Signal(Category.SCARCITY, m.value, Severity.HIGH)
        }
        Regex("(?U)only\\s+(\\d+)\\s+left", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            return Signal(
                Category.SCARCITY, m.value,
                if (n <= 3) Severity.HIGH else Severity.MEDIUM,
            )
        }
        Regex("low (?:in )?stock", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            return Signal(Category.SCARCITY, m.value, Severity.HIGH)
        }
        if (stockCount != null && stockCount in 1..3) {
            return Signal(Category.SCARCITY, "stock_count=$stockCount", Severity.HIGH)
        }
        return null
    }
}
