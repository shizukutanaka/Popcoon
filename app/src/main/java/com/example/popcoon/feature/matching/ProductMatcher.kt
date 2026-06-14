package com.example.popcoon.feature.matching

import com.example.popcoon.data.model.Product

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

        // 3. 正規化タイトルの Jaccard 類似度
        val titleSim = jaccardSimilarity(
            normalizeTitle(a.title),
            normalizeTitle(b.title),
        )

        // 型番一致は強いシグナル
        return if (modelMatch) {
            (0.7 + titleSim * 0.3).coerceAtMost(1.0)
        } else {
            titleSim
        }
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
        val normalized = toHalfWidth(title.lowercase())
            .replace(NOISE_REGEX, " ")
            .replace(SYMBOL_REGEX, " ")

        return normalized.split(WHITESPACE_REGEX)
            .filter { it.length >= 2 }
            .toSet()
    }

    /** 全角英数 (Ａ-Ｚａ-ｚ０-９) を半角に。全角タイトルを ASCII 経路と揃える。 */
    private fun toHalfWidth(s: String): String =
        s.replace(FULLWIDTH_REGEX) { m -> m.value.map { (it.code - 0xFEE0).toChar() }.joinToString("") }

    /**
     * 型番抽出: 英字+数字の組み合わせ (例: WH-1000XM5, RTX4090)。
     * 製品の一意識別に最も有効。
     *
     * 全角対応: 全角英数 (ＷＦ１０００) と全角ハイフン (－) を半角化してから MODEL_REGEX を当てる。
     * 以前は title.uppercase() のみで、全角表記の型番 (販売者が全角でタイトルを書く場合) を取りこぼし、
     * normalizeTitle (全角半角化済み) との整合が崩れていた。
     */
    fun extractModelNumber(title: String): String? {
        // 全角ハイフン(－) と全角スペース(U+3000) も半角化: MODEL_REGEX の [-\s] は ASCII のため、
        // 「ＲＴＸ　４０９０」のような全角区切りの型番を取りこぼさないように揃える。
        val ascii = toHalfWidth(title).replace('－', '-').replace('　', ' ').uppercase()
        val match = MODEL_REGEX.find(ascii) ?: return null
        return match.value.replace("-", "").replace(" ", "")
    }

    /** Jaccard 類似度 = 積集合 / 和集合 */
    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toDouble() / union
    }

    // 型番: 英字2文字以上 + 数字、またはハイフン区切り (WH-1000XM5, A2179, RTX-4090)
    private val MODEL_REGEX = Regex("[A-Z]{2,}[-\\s]?\\d{2,}[A-Z0-9-]*")

    // 全角英数字
    private val FULLWIDTH_REGEX = Regex("[Ａ-Ｚａ-ｚ０-９]+")

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
}
