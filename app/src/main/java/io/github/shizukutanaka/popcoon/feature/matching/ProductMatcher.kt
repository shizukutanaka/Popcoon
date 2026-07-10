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

        // 3. 正規化タイトルの Jaccard 類似度
        val titleSim = jaccardSimilarity(
            normalizeTitle(a.title),
            normalizeTitle(b.title),
        )

        return when {
            modelMatch -> (0.7 + titleSim * 0.3).coerceAtMost(1.0)
            modelMismatch -> titleSim * 0.5
            else -> titleSim
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
}
