package io.github.shizukutanaka.popcoon.feature.darkpattern

import io.github.shizukutanaka.popcoon.data.model.PriceRecord

/**
 * ダークパターン検出 — 景品表示法で禁止される誤認表示をアプリで可視化。
 * Python 実装 (popcoon_core.py::detect_dark_patterns) と同一ルール。
 */
object DarkPatternDetector {

    enum class WarningType {
        ALWAYS_ON_DISCOUNT,    // 常設セール (二重価格)
        INFLATED_LIST_PRICE,   // 参考価格誇張
        PRE_SALE_MARKUP,       // セール前値上げ
        CHARM_PRICING,         // 端数価格 (980円, 1,980円)
        FAKE_SCARCITY,         // 偽希少性 ("残り3点")
        COUNTDOWN_MANIPULATION,
        DRIP_PRICING,          // 隠れcoスト (送料・手数料が本体比過大)
    }

    enum class Severity { LOW, MEDIUM, HIGH }

    /**
     * @param label 固定の日本語文字列。Python オラクル (popcoon_core.py) との構築時比較・
     *   BuyTimingScorer の内部スコア内訳表示に使う (両者とも label の値自体をテスト・
     *   比較対象にはしていない — WarningType enum のみで判定している)。
     *   検索結果・商品詳細画面のユーザー向け警告表示はこれを直接使わず、
     *   ui/DarkPatternLabels.kt が type (+ DRIP_PRICING は severity/labelArgs) から
     *   ロケール対応の文字列リソースへマッピングする — DarkPatternDetector 自体は
     *   Android/リソース依存を持ち込まず Python と 1:1 対応する純粋ロジックのまま保つ。
     * @param labelArgs UI 層でのフォーマット引数 (DRIP_PRICING の割高率 % 等)。
     */
    data class Warning(
        val type: WarningType,
        val label: String,
        val severity: Severity,
        val labelArgs: List<Any> = emptyList(),
    )

    fun detect(
        currentPrice: Long,
        listPrice: Long?,
        history: List<PriceRecord>,
    ): List<Warning> {
        val warnings = mutableListOf<Warning>()

        // 1. 常設セール: 30日中90%超が listPrice 未満 (Python oracle と一致)
        if (listPrice != null && listPrice > currentPrice && history.size >= 30) {
            val belowRate = history.count { it.realPrice < listPrice }.toDouble() / history.size
            if (belowRate > 0.90) {
                warnings += Warning(WarningType.ALWAYS_ON_DISCOUNT, "常設セール", Severity.HIGH)
            }
        }

        // 2. 参考価格詐欺: listPrice が historic_high の 1.5倍超え
        if (listPrice != null && history.isNotEmpty()) {
            val maxReal = history.maxOf { it.realPrice }
            if (listPrice > maxReal * 1.5) {
                warnings += Warning(
                    WarningType.INFLATED_LIST_PRICE, "参考価格誇張", Severity.HIGH)
            }
        }

        // 3. セール前値上げ: 直近7日 vs 前7日で平均+10%超え + 今セール中
        if (history.size >= 14) {
            val recentAvg = history.takeLast(7).map { it.realPrice }.average()
            val prevAvg = history.subList(history.size - 14, history.size - 7)
                .map { it.realPrice }.average()
            if (recentAvg > prevAvg * 1.10 && listPrice != null && currentPrice < listPrice) {
                warnings += Warning(
                    WarningType.PRE_SALE_MARKUP, "セール前値上げ", Severity.HIGH)
            }
        }

        // 4. 端数価格: 下二桁が 80-99
        val lastTwo = currentPrice % 100
        if (lastTwo in 80..99) {
            warnings += Warning(WarningType.CHARM_PRICING, "端数価格", Severity.LOW)
        }

        return warnings
    }

    /**
     * Drip Pricing (隠れたコスト) 検出。
     *
     * 規制動向の知見:
     *  - Drip Pricing は「低い本体価格で釣り、後から送料・手数料を上乗せ」する手法。
     *    EU 消費者権利指令 Article 22、インド消費者保護局が規制対象に指定。
     *  - 表示価格 (本体) に対して送料・手数料が過大な場合に警告する。
     *
     * Popcoon は totalPrice (実質価格) を常時表示することでこれに対抗するが、
     * さらに乖離が大きい商品を能動的に警告し消費者を保護する。
     *
     * @param basePrice 表示される本体価格
     * @param totalPrice 送料・手数料込みの実質価格
     */
    fun detectDripPricing(basePrice: Long, totalPrice: Long): Warning? {
        if (basePrice <= 0) return null
        val extra = totalPrice - basePrice
        if (extra <= 0) return null
        val extraRate = extra.toDouble() / basePrice
        val pct = (extraRate * 100).toInt()
        return when {
            extraRate >= 0.30 -> Warning(
                WarningType.DRIP_PRICING,
                "送料・手数料が割高 (実質+${pct}%)",
                Severity.HIGH,
                labelArgs = listOf(pct),
            )
            extraRate >= 0.15 -> Warning(
                WarningType.DRIP_PRICING,
                "送料込みで割高 (実質+${pct}%)",
                Severity.MEDIUM,
                labelArgs = listOf(pct),
            )
            else -> null
        }
    }

    /**
     * テキストベースのダークパターン検出。
     *
     * arXiv 2411.07441 (AutoBot) の知見:
     *  - e-commerce で最頻出は fake-scarcity / fake-urgency
     *    (偽の希少性・緊急性。「残り3点」「本日限り」「タイムセール終了まで5分」)
     *  - VLLM は幻覚で false positive が多い → 軽量な正規表現ルールが堅実
     *  - オンデバイス処理でプライバシー保護 (I5 準拠、ネットワーク送信なし)
     *
     * 景品表示法・特定商取引法の観点でも、根拠なき希少性・緊急性表示は問題となりうる。
     * 商品タイトル・スニペットに対して実行する。
     */
    fun detectInText(text: String): List<Warning> {
        if (text.isBlank()) return emptyList()
        val warnings = mutableListOf<Warning>()

        // 偽の希少性: 在庫の切迫を煽る表現
        if (SCARCITY_PATTERN.containsMatchIn(text)) {
            warnings += Warning(
                WarningType.FAKE_SCARCITY,
                "在庫を煽る表現",
                Severity.MEDIUM,
            )
        }

        // 偽の緊急性: 時間制限を煽る表現
        if (URGENCY_PATTERN.containsMatchIn(text)) {
            warnings += Warning(
                WarningType.COUNTDOWN_MANIPULATION,
                "時間制限を煽る表現",
                Severity.MEDIUM,
            )
        }

        return warnings
    }

    // 「残り3点」「在庫わずか」「売り切れ間近」「あと2個」等
    private val SCARCITY_PATTERN = Regex(
        "残り\\s*\\d+\\s*(点|個|台|本|枚|箱)" +
            "|在庫\\s*(わずか|残りわずか|僅か)" +
            "|売り切れ(間近|寸前)" +
            "|あと\\s*\\d+\\s*(点|個)" +
            "|品切れ間近",
    )

    // 「本日限り」「タイムセール」「終了まで」「今だけ」「期間限定X分」等
    private val URGENCY_PATTERN = Regex(
        "本日(限り|のみ)" +
            "|タイムセール" +
            "|終了まで\\s*\\d+\\s*(分|時間|秒)" +
            "|今だけ" +
            "|期間限定\\s*\\d+\\s*(分|時間)" +
            "|まもなく終了" +
            "|締切間近" +
            "|急いで",
    )
}
