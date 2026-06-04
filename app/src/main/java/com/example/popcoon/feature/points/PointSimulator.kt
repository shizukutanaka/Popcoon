package com.example.popcoon.feature.points

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import java.time.DayOfWeek
import java.time.LocalDate
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

    data class PointSource(
        val name: String,
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
        // SPU 基本 (1倍 = 1%)
        val basePct = ctx.rakutenSpu.coerceIn(1, 15) / 100.0
        out += PointSource(
            "楽天SPU",
            (p.realPrice * basePct).toLong(),
            "${ctx.rakutenSpu}.0%",
        )

        // 5と0のつく日 (毎月 5/10/15/20/25/30 にエントリーで +1%)
        val day = ctx.purchaseDate.dayOfMonth
        if (day == 5 || day == 10 || day == 15 || day == 20 || day == 25 || day == 30) {
            out += PointSource(
                "5と0のつく日 +1%",
                (p.realPrice * 0.01).toLong(),
                "1.0%",
            )
        }

        // ダイヤモンド会員
        if (ctx.rakutenDiamondMember) {
            out += PointSource(
                "ダイヤモンド会員 +1%",
                (p.realPrice * 0.01).toLong(),
                "1.0%",
            )
        }
    }

    // ── Yahoo!ショッピング / PayPay ───────────────────────────────────────
    private fun applyYahooPoints(
        p: Product, ctx: UserContext, out: MutableList<PointSource>,
    ) {
        // 通常 PayPay 1%
        out += PointSource(
            "PayPay 基本",
            (p.realPrice * 0.01).toLong(),
            "1.0%",
        )

        // 5のつく日 +4%
        val day = ctx.purchaseDate.dayOfMonth
        if (day == 5 || day == 15 || day == 25) {
            out += PointSource(
                "5のつく日 +4%",
                (p.realPrice * 0.04).toLong(),
                "4.0%",
            )
        }

        // 日曜 +5%
        if (ctx.purchaseDate.dayOfWeek == DayOfWeek.SUNDAY) {
            out += PointSource(
                "日曜日 +5%",
                (p.realPrice * 0.05).toLong(),
                "5.0%",
            )
        }

        if (ctx.yahooPremium) {
            out += PointSource(
                "Yahoo!プレミアム +2%",
                (p.realPrice * 0.02).toLong(),
                "2.0%",
            )
        }

        if (ctx.paypaySoftbank) {
            out += PointSource(
                "SoftBank/Y!mobile +5%",
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
        if (p.pointsBack > 0) {
            val rate = p.pointsBack.toDouble() / max(1L, p.realPrice) * 100
            out += PointSource(
                "Amazon ポイント",
                p.pointsBack,
                "%.1f%%".format(rate),
            )
        }

        // Prime 配送料無料 (送料が 0 になる効果は別途 shipping で扱う)
    }
}
