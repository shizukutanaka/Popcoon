package io.github.shizukutanaka.popcoon.feature.crossborder

import kotlin.math.max

/**
 * 日本税関ルールでの越境EC着払い価格計算。
 * Python 実装 (popcoon_core.py::simulate_customs) と完全一致。
 */
object CustomsSimulator {

    enum class Verdict { CHEAPER, COMPARABLE, MORE_EXPENSIVE, NOT_RECOMMENDED }

    data class Result(
        val foreignPrice: Long,
        val shippingFee: Long,
        val dutiableValue: Long,
        val customsDuty: Long,
        val consumptionTax: Long,
        val handlingFee: Long,
        val totalLandedCost: Long,
        val isTaxExempt: Boolean,
        val verdict: Verdict,
    )

    private val DUTY_RATES = mapOf(
        "衣類" to 0.12,
        "靴" to 0.30,
        "バッグ" to 0.08,
        "電子機器" to 0.00,  // ITA 無税
        "カメラ" to 0.00,
        "おもちゃ" to 0.00,
        "スポーツ用品" to 0.04,
        "化粧品" to 0.03,
        "食品" to 0.20,
        "その他" to 0.06,
    )

    // 課税価格 (CIF) ¥16,666 = 商品代 ¥10,000 相当までの少額輸入は消費税・関税とも免税。
    // ⚠️ 将来変更 (2026-07 リサーチ): FY2026 税制改正大綱でこの少額輸入免税は 2028-04 に
    //   廃止決定 (海外プラットフォームが納税義務者に。個人輸入の 0.6掛け特例も廃止)。
    //   2026年中は現行どおり有効。廃止時期が来たらこの定数を 0 相当に更新すること。
    private const val TAX_EXEMPT_THRESHOLD = 16_666L

    // 消費税率: 標準10%、軽減税率8% (酒類・外食を除く飲食料品、2019年10月〜)。
    // カテゴリ体系に酒類/外食の区別が無いため「食品」カテゴリ全体に軽減税率を適用する
    // (酒類の混入は既知の簡略化 — UI 側で「概算」であることを開示する)。popcoon_core.py::
    // simulate_customs / naive_reference.py::naive_simulate_customs と同一ロジック。
    // ⚠️ 将来変更 (2026-07 リサーチ): 飲食料品を 2027-04 から2年間 1% に引き下げる案が
    //   審議中 (未成立)。成立したら REDUCED_TAX_RATE を時限で 0.01 に切替える (要オラクル同期)。
    private const val STANDARD_TAX_RATE = 0.10
    private const val REDUCED_TAX_RATE = 0.08

    fun simulate(
        foreignPriceJpy: Long,
        shippingJpy: Long,
        category: String = "その他",
        japanBestPrice: Long? = null,
    ): Result {
        val foreign = max(0, foreignPriceJpy)
        val ship = max(0, shippingJpy)

        val dutiable = foreign + ship
        val isExempt = dutiable <= TAX_EXEMPT_THRESHOLD

        val (duty, ctax, fee) = if (isExempt) {
            Triple(0L, 0L, 0L)
        } else {
            val rate = DUTY_RATES[category] ?: DUTY_RATES.getOrDefault("その他", 0.05)
            val taxRate = if (category == "食品") REDUCED_TAX_RATE else STANDARD_TAX_RATE
            val d = (dutiable * rate).toLong()
            val t = ((dutiable + d) * taxRate).toLong()
            Triple(d, t, 200L)
        }

        val total = foreign + ship + duty + ctax + fee

        // 判定順は Python オラクル (popcoon_core.simulate_customs) と厳密一致させる。
        // 食品/化粧品の NOT_RECOMMENDED は「価格でCHEAPER/同等/割高に該当しない」場合の
        // フォールバックであり、最優先ではない (免税級の掘り出し物はCHEAPERが勝つ)。
        val verdict = when {
            japanBestPrice == null -> Verdict.CHEAPER
            isExempt && total < japanBestPrice * 0.7 -> Verdict.CHEAPER
            total >= japanBestPrice -> Verdict.MORE_EXPENSIVE
            total >= japanBestPrice * 0.9 -> Verdict.COMPARABLE
            category == "食品" || category == "化粧品" -> Verdict.NOT_RECOMMENDED
            else -> Verdict.CHEAPER
        }

        return Result(
            foreignPrice = foreign,
            shippingFee = ship,
            dutiableValue = dutiable,
            customsDuty = duty,
            consumptionTax = ctax,
            handlingFee = fee,
            totalLandedCost = total,
            isTaxExempt = isExempt,
            verdict = verdict,
        )
    }
}
