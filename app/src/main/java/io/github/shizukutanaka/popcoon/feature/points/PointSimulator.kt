package io.github.shizukutanaka.popcoon.feature.points

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

/**
 * 日本EC ポイント還元シミュレーター。
 *
 * 同種ソフト調査結果:
 *  - ほぼやすねっと: ポイントキャンペーン分を計算して実質価格を算出
 *  - 最安値.com: クレジットカード/サービス選択 → 実質価格ランキング
 *  - 5のつく日 (Yahoo) / 楽天5と0のつく日キャンペーンを反映
 *
 * Popcoon の差別化:
 *  - すべての計算を pure function で実装 (TDD 可能)
 *  - 計算ロジックを公開 (透明性)
 *  - 競合全ての要素を統合 + 設定で個別 ON/OFF
 *
 * 「表面価格」と「実質価格」の差を可視化することで、
 * EC 各社のポイント戦略に惑わされない判断を支援。
 */
object PointSimulator {

    /**
     * ユーザーが持っている特典を表す。
     * すべて opt-in。デフォルトは何も適用しない。
     */
    data class UserContext(
        val rakutenDiamondMember: Boolean = false,    // 楽天ダイヤモンド会員
        val rakutenSpu: Int = 1,                      // SPU 倍率 (1-15)
        val yahooPremium: Boolean = false,            // Yahoo!プレミアム
        val paypaySoftbank: Boolean = false,          // SoftBank/Y!mobile (+5%)
        val amazonPrime: Boolean = false,
        val purchaseDate: LocalDate = LocalDate.now(),
    )

    data class Result(
        val sticker: Long,         // 表面価格 (税込)
        val shipping: Long,        // 送料
        val pointsBack: Long,      // ポイント還元額
        val effectivePrice: Long,  // 実質価格 = sticker + shipping - pointsBack
        val breakdown: List<PointSource>,  // 内訳 (透明性)
    )

    /** UI 層 (ui/PointSimulatorLabels.kt) が文字列リソースへマッピングする際の安定識別子。 */
    enum class Kind {
        RAKUTEN_SPU, RAKUTEN_5_0_DAY, RAKUTEN_DIAMOND,
        YAHOO_PAYPAY_BASE, YAHOO_5_DAY, YAHOO_SUNDAY, YAHOO_PREMIUM, YAHOO_SOFTBANK,
        AMAZON_POINTS,
    }

    /**
     * @param name 内部識別用の固定日本語文字列。PointSimulatorTest.kt が
     *   `.name.contains(...)` で判定に使うため保持する。
     * @param kind UI 表示用ローカライズ文字列を引くための安定識別子
     *   (ui/PointSimulatorLabels.kt の `PointSource.nameRes()` が使用)。
     */
    data class PointSource(
        val name: String,
        val kind: Kind,
        val amount: Long,
        val rateString: String,   // "1.0%" など表示用
    )

    /**
     * 商品 + ユーザー context から実質価格を計算。
     */
    fun simulate(product: Product, ctx: UserContext = UserContext()): Result {
        val sticker = product.realPrice
        val shipping = product.shippingFee
        val sources = mutableListOf<PointSource>()

        when (product.platform) {
            Platform.RAKUTEN -> applyRakutenPoints(product, ctx, sources)
            Platform.YAHOO -> applyYahooPoints(product, ctx, sources)
            Platform.AMAZON -> applyAmazonPoints(product, ctx, sources)
        }

        val totalPoints = sources.sumOf { it.amount }
        val effective = max(0L, sticker + shipping - totalPoints)

        return Result(
            sticker = sticker,
            shipping = shipping,
            pointsBack = totalPoints,
            effectivePrice = effective,
            breakdown = sources,
        )
    }

    // ── 楽天市場 ────────────────────────────────────────────────────────────
    private fun applyRakutenPoints(
        p: Product, ctx: UserContext, out: MutableList<PointSource>,
    ) {
        // SPU 基本 (1倍 = 1%)。表示率は実際に付与する coerce 後の値に揃える
        // (透明性: 生の rakutenSpu を表示すると amount と矛盾していた。
        //  spu=0 → 1% 付与なのに "0.0%" 表示、spu=21 → 18% 付与なのに "21.0%" 表示)。
        // 上限 18: 2026-07-01 の SPU 改定でプログラム上限が 18.5倍 (=最大 +17.5% ボーナス
        // + 基本1%) に引き上げられた。本フィールドは整数%モデルのため 18 で近似する
        // (半端な 0.5% 分は切り捨て — サービス毎のポイント上限もあり実際の付与はこれ以下)。
        val spu = ctx.rakutenSpu.coerceIn(1, 18)
        out += PointSource(
            "楽天SPU",
            Kind.RAKUTEN_SPU,
            (p.realPrice * (spu / 100.0)).toLong(),
            "$spu.0%",
        )

        // 5と0のつく日 (毎月 5/10/15/20/25/30 にエントリーで +1%)
        val day = ctx.purchaseDate.dayOfMonth
        if (day == 5 || day == 10 || day == 15 || day == 20 || day == 25 || day == 30) {
            out += PointSource(
                "5と0のつく日 +1%",
                Kind.RAKUTEN_5_0_DAY,
                (p.realPrice * 0.01).toLong(),
                "1.0%",
            )
        }

        // ダイヤモンド会員
        if (ctx.rakutenDiamondMember) {
            out += PointSource(
                "ダイヤモンド会員 +1%",
                Kind.RAKUTEN_DIAMOND,
                (p.realPrice * 0.01).toLong(),
                "1.0%",
            )
        }
    }

    // ── Yahoo!ショッピング / PayPay ───────────────────────────────────────
    // 注: 2025-02 以降、キャンペーン由来のポイント (5のつく日・日曜特典等) は
    // 「PayPayポイント (期間限定)」で付与され約30日〜月末で失効する (2026-07 リサーチ)。
    // 本シミュレーターは金額換算のみ行い失効リスクは扱わない — UI 側の注記で開示する。
    private fun applyYahooPoints(
        p: Product, ctx: UserContext, out: MutableList<PointSource>,
    ) {
        // 通常 PayPay 1%
        out += PointSource(
            "PayPay 基本",
            Kind.YAHOO_PAYPAY_BASE,
            (p.realPrice * 0.01).toLong(),
            "1.0%",
        )

        // 5のつく日 +4%
        val day = ctx.purchaseDate.dayOfMonth
        if (day == 5 || day == 15 || day == 25) {
            out += PointSource(
                "5のつく日 +4%",
                Kind.YAHOO_5_DAY,
                (p.realPrice * 0.04).toLong(),
                "4.0%",
            )
        }

        // プレミアムな日曜日 +5% (2026-07 リサーチで確認: 旧ソフトバンク日曜特典は
        // 2022-10 終了。現行は LYPプレミアム会員 or SoftBank/Y!mobile ユーザー限定で、
        // 1注文 5,000 円以上が条件)。従来は無条件で +5% しており、条件を満たさない
        // ユーザーに過大なポイント額を提示していた。realPrice を注文額の近似として使う。
        // なお 11日/22日の「ヤフショ感謝デー」(会員ランク シルバー+4%/ゴールド+5%) は
        // UserContext にランク次元が無いため未実装 — SaleCalendar 側で情報表示のみ行う。
        if (ctx.purchaseDate.dayOfWeek == DayOfWeek.SUNDAY &&
            (ctx.yahooPremium || ctx.paypaySoftbank) &&
            p.realPrice >= 5000
        ) {
            out += PointSource(
                "日曜日 +5%",
                Kind.YAHOO_SUNDAY,
                (p.realPrice * 0.05).toLong(),
                "5.0%",
            )
        }

        if (ctx.yahooPremium) {
            out += PointSource(
                "Yahoo!プレミアム +2%",
                Kind.YAHOO_PREMIUM,
                (p.realPrice * 0.02).toLong(),
                "2.0%",
            )
        }

        if (ctx.paypaySoftbank) {
            out += PointSource(
                "SoftBank/Y!mobile +5%",
                Kind.YAHOO_SOFTBANK,
                (p.realPrice * 0.05).toLong(),
                "5.0%",
            )
        }
    }

    // ── Amazon ─────────────────────────────────────────────────────────────
    private fun applyAmazonPoints(
        p: Product, ctx: UserContext, out: MutableList<PointSource>,
    ) {
        // Amazon は商品ごとにポイント設定 (固定還元なし) → product.pointsBack を使う
        // realPrice > 0 でなければ率の計算が無意味 (無料商品や価格不明)
        if (p.pointsBack > 0 && p.realPrice > 0) {
            val rate = p.pointsBack.toDouble() / p.realPrice * 100
            out += PointSource(
                "Amazon ポイント",
                Kind.AMAZON_POINTS,
                p.pointsBack,
                // Locale.US 固定: 既定ロケールに任せると独語等で「1,5%」になり、
                // CurrencyFormatter (Locale.US) と小数点表記が不一致になる。
                String.format(Locale.US, "%.1f%%", rate),
            )
        }

        // Prime 配送料無料 (送料が 0 になる効果は別途 shipping で扱う)
    }
}
