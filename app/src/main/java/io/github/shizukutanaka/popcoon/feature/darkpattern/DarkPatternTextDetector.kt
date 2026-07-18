package io.github.shizukutanaka.popcoon.feature.darkpattern

/**
 * UIテキスト系ダークパターン検出
 * （URGENCY / SCARCITY / SOCIAL_PROOF / MISDIRECTION / FORCED_ACTION / HIDDEN_SUBSCRIPTION）。
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
    enum class Category { FORCED_ACTION, HIDDEN_SUBSCRIPTION, MISDIRECTION, SCARCITY, SOCIAL_PROOF, URGENCY }

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
    private val URGENCY = listOf(
        Regex("(?U)残り\\s*\\d+\\s*(?:時間|分|秒)"),
        Regex("本日限り"),
        Regex("今だけ"),
        Regex("まもなく(?:終了|締切)"),
        Regex("ending soon", RegexOption.IGNORE_CASE),
        Regex("limited[- ]time", RegexOption.IGNORE_CASE),
        Regex("(?U)\\bhurry\\b", RegexOption.IGNORE_CASE),
        Regex("act now", RegexOption.IGNORE_CASE),
        Regex("today only", RegexOption.IGNORE_CASE),
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

        return warnings.sortedBy { it.category }
    }

    // ── プライベートヘルパー ─────────────────────────────────────────────────

    private fun firstMatch(text: String, patterns: List<Regex>): String? {
        for (p in patterns) p.find(text)?.let { return it.value }
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
