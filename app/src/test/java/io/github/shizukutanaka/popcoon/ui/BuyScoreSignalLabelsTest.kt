package io.github.shizukutanaka.popcoon.ui

import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * BuyTimingScorer.Signal.toLabelResource() のテスト。
 *
 * Signal.name (日本語固定文字列、BuyTimingScorerTest.kt / Python オラクルが厳密比較する
 * 内部識別子) を検索結果・商品詳細画面のスコア内訳に直接表示すると EN/KO/ZH ロケールに
 * 日本語が漏れる (商用リリース監査で発見)。このマッピングが kind から正しい文字列
 * リソースへ変換することを固定する。SignalKind の when 式は Kotlin コンパイラにより
 * 網羅性チェックされる (else 分岐なし) — 新しい SignalKind を追加してマッピングを
 * 忘れるとコンパイルエラーになる。
 */
class BuyScoreSignalLabelsTest : StringSpec({

    fun signal(kind: BuyTimingScorer.SignalKind, args: List<Any> = emptyList()) =
        BuyTimingScorer.Signal("dummy", 0, kind, args)

    "静的シグナルは引数なしで対応する文字列リソースにマップされる" {
        signal(BuyTimingScorer.SignalKind.NEUTRAL_BASE).toLabelResource() shouldBe
            (R.string.buy_score_neutral_base to emptyList<Any>())
        signal(BuyTimingScorer.SignalKind.ATL_REACHED).toLabelResource() shouldBe
            (R.string.buy_score_atl_reached to emptyList<Any>())
        signal(BuyTimingScorer.SignalKind.TREND_DOWN).toLabelResource() shouldBe
            (R.string.buy_score_trend_down to emptyList<Any>())
        signal(BuyTimingScorer.SignalKind.HISTORY_INSUFFICIENT).toLabelResource() shouldBe
            (R.string.buy_score_history_insufficient to emptyList<Any>())
        signal(BuyTimingScorer.SignalKind.DARK_PATTERN_DETECTED).toLabelResource() shouldBe
            (R.string.buy_score_dark_pattern_detected to emptyList<Any>())
    }

    "DISCOUNT_PCT は kindArgs (割引率) をそのまま引き継ぐ" {
        signal(BuyTimingScorer.SignalKind.DISCOUNT_PCT, listOf(25)).toLabelResource() shouldBe
            (R.string.buy_score_discount_pct to listOf(25))
    }

    "DISCOUNT_PCT_MINOR は DISCOUNT_PCT と異なるリソースにマップされる (僅少表記)" {
        val minor = signal(BuyTimingScorer.SignalKind.DISCOUNT_PCT_MINOR, listOf(5)).toLabelResource()
        val normal = signal(BuyTimingScorer.SignalKind.DISCOUNT_PCT, listOf(5)).toLabelResource()
        minor.first shouldBe R.string.buy_score_discount_pct_minor
        (minor.first == normal.first) shouldBe false
    }

    "SaleCalendar の nextMajorSale から得た実際の Signal も正しくマップされる" {
        // 大型セール直前 (0-3日以内) の日付を使い、signalUpcomingSale() の実出力を検証。
        val today = java.time.LocalDate.of(2026, 7, 14)  // Amazon プライムデー (7/16-17) の2日前
        val score = BuyTimingScorer.score(
            current = 5000, listPrice = 6000,
            history = (0 until 20).map {
                io.github.shizukutanaka.popcoon.data.model.PriceRecord(
                    productKey = "p", platform = "amazon",
                    listPrice = 6000, realPrice = 5000,
                    recordedAt = java.time.Instant.parse("2026-06-${(it + 1).toString().padStart(2, '0')}T00:00:00Z"),
                )
            },
            today = today,
        )
        val saleSignal = score?.signals?.find { it.kind == BuyTimingScorer.SignalKind.SALE_IMMINENT }
        saleSignal?.toLabelResource()?.first shouldBe R.string.buy_score_sale_imminent
    }
})
