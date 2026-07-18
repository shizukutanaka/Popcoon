package io.github.shizukutanaka.popcoon.feature.matching

import io.github.shizukutanaka.popcoon.data.model.Product
import java.text.Normalizer

/**
 * クロスプラットフォーム商品名寄せ (Product Matching)。
 *
 * 価格比較アプリの核心課題: Amazon・楽天・Yahoo で同一商品をどう同定するか。
 *
 * arXiv 2512.07232 / 1907 の知見:
 *  - 2段階パイプライン (Rough Filtering → Fine Filtering) が有効
 *  - 商品タイトル + 属性の両方を使うとマッチ精度が向上
 *  - BERT 等の重量級は精度高いがオンデバイス困難
 *
 * Popcoon の制約 (ゼロ依存・オンデバイス・ゼロコスト) に従い、
 * Rough Filtering 相当の決定的ルールベースで実装する:
 *  1. JAN コード一致 → 確実な同一商品 (信頼度 1.0)
 *  2. 型番一致 + タイトル類似 → 高信頼 (0.8+)
 *  3. 正規化タイトルの Jaccard 類似度 → スコア化
 *
 * 重量級 ML を避けることで:
 *  - ネットワーク送信なし (I5 プライバシー準拠)
 *  - 決定的で再現性あり (誤検出のデバッグが容易)
 *  - 低レイテンシ (p99 < 1ms)
 */
object ProductMatcher {

    /** マッチ判定の閾値。これ以上で「同一商品の可能性が高い」 */
    const val MATCH_THRESHOLD = 0.6

    /**
     * 文字 2-gram Dice をブレンドする際の減衰係数 (研究 2-2)。
     * 2-gram Dice はブランド+カテゴリ語を共有するだけの別商品 (イヤホン vs ヘッドホン、
     * raw dice ≈ 0.73) を系統的に高く見積もるため、0.75 を掛けてから Jaccard と max() する。
     * 0.75 × 0.73 ≈ 0.545 < 0.6 で誤マッチを回避しつつ、分かち書き有無だけが違う同一商品
     * (dice = 1.0 → 0.75 ≥ 0.6) は救済できる、実測に基づく境界値。
     */
    const val BIGRAM_DICE_WEIGHT = 0.75

    /**
     * 2商品の同一性スコア (0.0-1.0) を計算。
     */
    fun similarity(a: Product, b: Product): Double {
        // 1. JAN コード一致 → 確実
        val janA = a.janCode
        val janB = b.janCode
        if (!janA.isNullOrBlank() && janA == janB) return 1.0

        // 2. 型番抽出して一致するか
        val modelA = extractModelNumber(a.title)
        val modelB = extractModelNumber(b.title)
        val modelMatch = modelA != null && modelA == modelB
        // 両方から型番が取れたのに値が食い違う = 「別モデル/別世代」の確定情報
        // (例: WH-1000XM4 vs WH-1000XM5、iPhone 15 128GB vs 256GB — extractModelNumber は
        // 容量サフィックスも型番に連結するため異なる SKU として区別できる)。
        // これは「型番が分からない」場合より遥かに強い負のシグナルなので、
        // 単純にタイトル類似度 (ブランド名・カテゴリ語だけで簡単に閾値を超えてしまう) に
        // フォールバックせず明示的に減点する (機能過不足監査で発見: 異なる世代のヘッドホンや
        // 異なる容量の iPhone を「同一商品」と誤って統合し、具体的な節約額を提示していた)。
        val modelMismatch = modelA != null && modelB != null && modelA != modelB

        // 3. タイトル類似度 = トークン Jaccard と 文字 2-gram Dice のブレンド。
        //    日本語 EC タイトルは分かち書きが無いことが多く、トークン Jaccard は
        //    「タイトル全体が 1 トークン」に退化する (同一商品でも 0)。文字 2-gram Dice は
        //    空白に依存しないためこれを救済する (研究 2-2)。ただし 2-gram Dice は
        //    ブランド+カテゴリ語を共有するだけの別商品 (イヤホン vs ヘッドホン) を系統的に
        //    高く見積もるため、BIGRAM_DICE_WEIGHT で減衰してから max() でブレンドする。
        val titleSim = titleSimilarity(a.title, b.title)

        val base = when {
            modelMatch -> (0.7 + titleSim * 0.3).coerceAtMost(1.0)
            modelMismatch -> titleSim * 0.5
            else -> titleSim
        }

        // 4. 属性不一致ペナルティ (WDC Products ベンチマークの知見: 名寄せの precision は
        // 「ほぼ同一だが別 SKU」のコーナーケースで最も落ちる — 2026-07 リサーチ)。
        // 個数 (2個 vs 4個) や色 (ブルー vs レッド) の食い違いは、型番が一致していても
        // 別 SKU (別価格) の強いシグナル。型番一致の 0.7 底上げだけでは閾値 0.6 を
        // 下回らないため、乗算ペナルティで確実に落とす (0.9 × 0.5 = 0.45 < 0.6)。
        // 両者から属性が取れて食い違う場合のみ減点 (どちらかが不明なら中立 — 保守的)。
        var penalty = 1.0
        val qtyA = extractQuantity(a.title)
        val qtyB = extractQuantity(b.title)
        if (qtyA != null && qtyB != null && qtyA != qtyB) penalty *= 0.5
        val colorA = extractColor(a.title)
        val colorB = extractColor(b.title)
        if (colorA != null && colorB != null && colorA != colorB) penalty *= 0.6
        // 内容量/重量の食い違い (洗剤 500ml vs 1L、コーヒー 200g vs 500g) も別 SKU の
        // 強いシグナル。個数/色と同じく両者から一意に取れて食い違う場合のみ減点する。
        // 同ドメイン (液体 ml / 重量 mg) 同士で量が違うときだけ — ml と g の偶然の
        // 数字一致は不一致とみなさない (保守的)。
        val volA = extractVolume(a.title)
        val volB = extractVolume(b.title)
        if (volA != null && volB != null && volA.domain == volB.domain && volA.baseAmount != volB.baseAmount) {
            penalty *= 0.5
        }

        return base * penalty
    }

    /** 同一商品とみなせるか */
    fun isMatch(a: Product, b: Product): Boolean = similarity(a, b) >= MATCH_THRESHOLD

    /**
     * 商品リストを同一商品グループにまとめる。
     * 各グループは最安の totalPrice 順にソート済み。
     *
     * 2段階 (arXiv 2512.07232 Rough Filtering):
     *  1. JAN コードがある商品は JAN でバケット化 (確実 & 高速 O(n))
     *  2. JAN がない商品のみタイトル類似度で照合 (O(m²), m = JAN なし件数)
     */
    fun groupByIdentity(products: List<Product>): List<List<Product>> {
        val groups = mutableListOf<MutableList<Product>>()

        // 1. JAN コードで確実にバケット化
        val byJan = HashMap<String, MutableList<Product>>()
        val noJan = mutableListOf<Product>()
        for (p in products) {
            val jan = p.janCode
            if (!jan.isNullOrBlank()) {
                byJan.getOrPut(jan) { mutableListOf() } += p
            } else {
                noJan += p
            }
        }
        groups += byJan.values

        // 2. JAN なしはタイトル類似度で照合
        //    既存 JAN グループにも合流できるなら合流 (型番一致など)
        //    g.any: JAN-less 商品が作ったグループでは g.first() だけでなく
        //    全メンバーと照合しないと、後発の JAN-less 商品を取り込み損ねる。
        for (p in noJan) {
            val group = groups.firstOrNull { g -> g.any { isMatch(it, p) } }
            if (group != null) {
                group += p
            } else {
                groups += mutableListOf(p)
            }
        }

        return groups.map { g -> g.sortedBy { it.totalPrice } }
    }

    /**
     * タイトル正規化:
     *  - 全角→半角、大文字→小文字
     *  - 記号・空白除去
     *  - ノイズ語 (送料無料・正規品・新品等) 除去
     */
    fun normalizeTitle(title: String): Set<String> {
        val normalized = nfkc(title).lowercase()
            .replace(NOISE_REGEX, " ")
            .replace(SYMBOL_REGEX, " ")

        return normalized.split(WHITESPACE_REGEX)
            .filter { it.length >= 2 }
            .toSet()
    }

    /**
     * Unicode NFKC 正規化。全角英数→半角、全角ハイフン(－)/全角スペース(U+3000)→ASCII、
     * **半角カナ→全角カナ** (ｿﾆｰ→ソニー)、濁点合成 (ﾊﾞ→バ) を一括で行う。
     * 日本語 EC タイトルの表記ゆれを吸収し、名寄せの取りこぼしを防ぐ標準的手段。
     * (以前は全角英数のみの手製変換で、半角カナ・全角区切りを取りこぼしていた)
     */
    private fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)

    /**
     * 型番抽出: 英字+数字の組み合わせ (例: WH-1000XM5, RTX4090)。製品の一意識別に最も有効。
     * NFKC 正規化で全角表記の型番 (ＷＦ－１０００ＸＭ４ / ＲＴＸ　４０９０) も拾う。
     *
     * 型番直後 (空白 0〜1 個を挟んでもよい) に容量表記 (128GB / 256GB / 1TB 等) が
     * 続く場合は型番に連結する。MODEL_REGEX 単体では "iPhone 15 128GB" と
     * "iPhone 15 256GB" が共に "IPHONE15" に丸められ同一型番と誤判定していた
     * (機能過不足監査で発見)。同一シリーズでも容量違いは別 SKU (別価格) であり、
     * 同一商品として名寄せしてはならない。
     */
    fun extractModelNumber(title: String): String? {
        val normalized = nfkc(title).uppercase()
        val match = MODEL_REGEX.find(normalized) ?: return null
        val base = match.value.replace("-", "").replace(" ", "")
        val afterModel = normalized.substring(match.range.last + 1)
        val capacity = CAPACITY_REGEX.find(afterModel)
            ?.takeIf { it.range.first <= 1 }  // 型番の直後 (空白最大1個) のみ連結対象
        return if (capacity != null) base + capacity.value.replace(" ", "") else base
    }

    /** Jaccard 類似度 = 積集合 / 和集合 */
    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toDouble() / union
    }

    /**
     * ブレンド済みタイトル類似度 = max(トークン Jaccard, BIGRAM_DICE_WEIGHT × 文字2-gram Dice)。
     * Python オラクル `proto_title_similarity.title_similarity()` と厳密一致 (研究 2-2)。
     * - 空白区切りが機能するタイトル → 語順に頑健な Jaccard が支配
     * - 分かち書きなしタイトル → Dice が救済 (同一内容なら 0.75×1.0 = 0.75 ≥ 閾値 0.6)
     */
    internal fun titleSimilarity(titleA: String, titleB: String): Double {
        val jaccard = jaccardSimilarity(normalizeTitle(titleA), normalizeTitle(titleB))
        val dice = BIGRAM_DICE_WEIGHT * charBigramDice(titleA, titleB)
        return maxOf(jaccard, dice)
    }

    /**
     * 正規化タイトルの文字 2-gram 集合 Dice 係数 (0.0-1.0)。2 文字未満は 0。
     * normalizeTitle() と同じ前段正規化 (NFKC → 小文字 → ノイズ語/記号除去) を共有し、
     * トークン化の代わりに空白を全除去した 1 本の文字列から 2-gram を作る。
     */
    internal fun charBigramDice(titleA: String, titleB: String): Double {
        val a = charBigrams(normalizeForBigrams(titleA))
        val b = charBigrams(normalizeForBigrams(titleB))
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        return 2.0 * intersection / (a.size + b.size)
    }

    /** normalizeTitle() の前段正規化から空白を全除去した 1 本の文字列 (2-gram 用)。 */
    private fun normalizeForBigrams(title: String): String =
        nfkc(title).lowercase()
            .replace(NOISE_REGEX, " ")
            .replace(SYMBOL_REGEX, " ")
            .replace(WHITESPACE_REGEX, "")

    private fun charBigrams(s: String): Set<String> {
        if (s.length < 2) return emptySet()
        return (0 until s.length - 1).mapTo(HashSet()) { s.substring(it, it + 2) }
    }

    /**
     * 内容量/重量属性。domain = "ml" (液体) / "g" (重量) のラベル。
     * baseAmount の単位はそれぞれ ml / mg (重量は最小単位 mg で保持し小数丸め衝突を防ぐ)。
     */
    data class Volume(val domain: String, val baseAmount: Long)

    /**
     * 内容量/重量の抽出 (Python: proto_volume_attr.extract_volume と厳密一致)。
     * - 液体: ml 基準 (1L / 1リットル / 1ℓ = 1000ml)
     * - 重量: mg 基準 (1g = 1000mg, 1kg = 1,000,000mg)
     * 誤爆対策: 素の「g」は小文字のみ (ネットワーク「5G」を拾わない)、素の「ミリ」は
     * 拾わない (「5ミリ」は長さ mm)。液体と重量が両方一意に出た場合は曖昧なので null。
     * 複数の異なる量が出た場合も null (extractQuantity/extractColor と同じ保守方針)。
     */
    internal fun extractVolume(title: String): Volume? {
        val normalized = nfkc(title)
        val liquids = VOLUME_LIQUID_REGEX.findAll(normalized)
            .map { (it.groupValues[1].toDouble() * liquidFactor(it.groupValues[2])).toLong() }
            .toSet()
        val weights = VOLUME_WEIGHT_REGEX.findAll(normalized)
            .map { (it.groupValues[1].toDouble() * weightFactor(it.groupValues[2])).toLong() }
            .toSet()
        val liquid = liquids.singleOrNull()
        val weight = weights.singleOrNull()
        return when {
            liquid != null && weight == null -> Volume("ml", liquid)
            weight != null && liquid == null -> Volume("g", weight)
            else -> null  // 無し / 両ドメイン / 曖昧 → 中立
        }
    }

    private fun liquidFactor(unit: String): Double = when (unit.lowercase()) {
        "リットル", "l", "ℓ" -> 1000.0
        else -> 1.0  // ミリリットル / ml / cc
    }

    private fun weightFactor(unit: String): Double = when (unit.lowercase()) {
        "kg", "キロ" -> 1_000_000.0
        "g", "グラム" -> 1000.0
        else -> 1.0  // mg
    }

    /**
     * 個数属性の抽出: 「24本」「3個セット」等の数量+助数詞。
     * 複数の異なる数量が出る (「2個セット 合計4個」等) 場合は曖昧なので null (中立)。
     * NFKC 正規化で全角数字にも対応。
     */
    internal fun extractQuantity(title: String): Int? {
        val normalized = nfkc(title)
        val counts = QUANTITY_REGEX.findAll(normalized)
            .map { it.groupValues[1].toInt() }
            .toSet()
        return counts.singleOrNull()
    }

    /**
     * 色属性の抽出 → 正準色名 (BLACK/WHITE/...)。日英表記を同一視する。
     * 複数の異なる色が出る (カラバリ一覧タイトル) 場合は曖昧なので null (中立)。
     * カタカナ色名は前後がカタカナだと不採用 (「ブルーレイ」の ブルー 等の誤抽出防止)。
     * 漢字1文字の色 (黒/金/銀) は「黒糖」「金曜」等の複合語誤爆が多いため対象外 —
     * 取れない側は null = 中立になるだけで安全側に倒れる。
     */
    internal fun extractColor(title: String): String? {
        val normalized = nfkc(title).uppercase()
        val colors = COLOR_REGEX.findAll(normalized)
            .map { canonicalColor(it.value) }
            .toSet()
        return colors.singleOrNull()
    }

    private fun canonicalColor(matched: String): String = when (matched) {
        "ブラック" -> "BLACK"
        "ホワイト", "アイボリー" -> "WHITE"  // アイボリーは白系。過剰分離を避け白に寄せる (保守的)
        "ネイビーブルー", "ネイビー" -> "NAVY"
        "スカイブルー", "ライトブルー", "ターコイズ", "ブルー" -> "BLUE"
        "ワインレッド", "レッド" -> "RED"    // ワインレッドは赤系。過剰分離を避け赤に寄せる
        "モスグリーン", "グリーン" -> "GREEN"
        "イエロー" -> "YELLOW"
        "ピンク" -> "PINK"
        "ラベンダー", "パープル" -> "PURPLE"
        "オレンジ" -> "ORANGE"
        "キャメル", "ブラウン" -> "BROWN"
        "ベージュ" -> "BEIGE"
        "シルバー" -> "SILVER"
        "ゴールド" -> "GOLD"
        "チャコール", "グレー", "グレイ", "GREY" -> "GRAY"  // チャコールは濃灰系
        // カーキは黄土/オリーブ系で既存のどの正準色とも明確に異なる独立色 (別カラバリ=別SKU)
        "カーキ" -> "KHAKI"
        else -> matched  // 英語表記 (BLACK 等) はそのまま正準名
    }

    // 型番: 英字2文字以上 + 数字、またはハイフン区切り (WH-1000XM5, A2179, RTX-4090)
    private val MODEL_REGEX = Regex("[A-Z]{2,}[-\\s]?\\d{2,}[A-Z0-9-]*")

    // 型番直後に続く容量表記 (128GB, 256GB, 1TB 等)。extractModelNumber() が型番に連結し、
    // 同一シリーズの容量違い SKU (iPhone 15 128GB vs 256GB) を型番一致から除外する。
    private val CAPACITY_REGEX = Regex("\\d+\\s?(?:GB|TB|MB)")

    // ノイズ語 (マッチに無関係な販促語)
    private val NOISE_REGEX = Regex(
        "送料無料|正規品|新品|未使用|即日発送|あす楽|ポイント\\d*倍|" +
            "公式|国内正規|メーカー保証|限定|セール|お買い得|人気|おすすめ",
    )

    // 記号類
    private val SYMBOL_REGEX = Regex("[\\[\\]【】（）()「」『』、。,.!！?？/／・:：;；\"'`~〜\\-_=+*#@&|]")

    // (?U): 全角スペース (U+3000) でも分割する。ASCII \s だと全角タイトルが分割されず
    // 巨大な 1 トークンになり Jaccard 類似度が崩れていた。
    private val WHITESPACE_REGEX = Regex("(?U)\\s+")

    // 個数属性: 数字 + 助数詞 (+任意の セット/入り/パック)。「500ml」等の単位は対象外。
    private val QUANTITY_REGEX =
        Regex("(\\d+)\\s*(?:個|本|枚|袋|包|錠|巻|組|足|着|缶|箱)(?:入り?|セット|パック)?")

    // 液体量: 数字(小数可) + 単位。大小無視で安全 (5G のような一般的誤爆源が無い)。
    // 長い単位を先に (ミリリットル を リットル より優先)。
    private val VOLUME_LIQUID_REGEX =
        Regex("(\\d+(?:\\.\\d+)?)\\s*(ミリリットル|リットル|ml|cc|ℓ|l)", RegexOption.IGNORE_CASE)

    // 重量: 素の「g」はネットワーク「5G」誤爆を避けるため小文字のみ (IGNORE_CASE 不使用)。
    // kg/mg は大小両方許容。長い単位を先に。
    private val VOLUME_WEIGHT_REGEX =
        Regex("(\\d+(?:\\.\\d+)?)\\s*(グラム|キロ|[kK][gG]|[mM][gG]|kg|g)")

    // 色属性: 長い語を先に (ネイビーブルー を ブルー より優先、モスグリーン を グリーン より、
    // ワインレッド を レッド より)。カタカナ色名は前後がカタカナだと不採用
    // (ブルーレイ/マットブラック系の複合語誤爆防止)。英語は単語境界。
    private val COLOR_REGEX = Regex(
        "(?<![ァ-ヶー])(?:ネイビーブルー|スカイブルー|ライトブルー|モスグリーン|ワインレッド|" +
            "ターコイズ|ラベンダー|アイボリー|チャコール|キャメル|カーキ|" +
            "ブラック|ホワイト|シルバー|ゴールド|" +
            "パープル|オレンジ|グリーン|イエロー|ブラウン|ベージュ|ネイビー|グレー|グレイ|ブルー|レッド|ピンク)(?![ァ-ヶー])|" +
            "\\b(?:BLACK|WHITE|SILVER|GOLD|PURPLE|ORANGE|GREEN|YELLOW|BROWN|BEIGE|NAVY|GRAY|GREY|BLUE|RED|PINK|KHAKI)\\b",
    )
}
