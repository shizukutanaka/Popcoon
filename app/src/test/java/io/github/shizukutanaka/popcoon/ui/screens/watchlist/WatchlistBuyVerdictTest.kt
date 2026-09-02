package io.github.shizukutanaka.popcoon.ui.screens.watchlist

import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * watchlistBuyVerdict (ウォッチリスト行の買い時バッジ用マッピング) の仕様テスト。
 *
 * 判定ロジック本体は WidgetVerdictTest で網羅済みなので、ここでは
 * 「WidgetVerdict(String) → BuyTimingScorer.Verdict?」のマッピングと、
 * 「NEUTRAL はバッジを出さない (null)」という製品判断を固定する。
 */
class WatchlistBuyVerdictTest : StringSpec({

    fun item(real: Long, target: Long? = null, added: Long = 0): WatchlistItem =
        WatchlistItem(
            productKey = "amazon:X",
            sku = "X",
            title = "t",
            platform = "amazon",
            realPrice = real,
            listPrice = real,
            url = "",
            imageUrl = null,
            targetPrice = target,
            addedPrice = added,
        )

    "目標価格に到達 → BUY_NOW" {
        watchlistBuyVerdict(item(real = 900, target = 1000)) shouldBe
            BuyTimingScorer.Verdict.BUY_NOW
    }

    "追加時から有意に下落 → BUY_NOW" {
        // 1000 → 900 (−10%) は閾値 5% 超
        watchlistBuyVerdict(item(real = 900, added = 1000)) shouldBe
            BuyTimingScorer.Verdict.BUY_NOW
    }

    "追加時から有意に上昇 → WAIT" {
        // 1000 → 1100 (+10%)
        watchlistBuyVerdict(item(real = 1100, added = 1000)) shouldBe
            BuyTimingScorer.Verdict.WAIT
    }

    "横ばい・基準なし (NEUTRAL) → null でバッジ非表示" {
        watchlistBuyVerdict(item(real = 1000, added = 1000)) shouldBe null
        watchlistBuyVerdict(item(real = 1000)) shouldBe null
    }

    "価格不明 (realPrice <= 0) → null" {
        watchlistBuyVerdict(item(real = 0, target = 500)) shouldBe null
    }

    // 境界: 目標価格「ちょうど」も到達とみなす (WidgetVerdict は realPrice <= targetPrice)。
    // ユーザーが明示した条件なので、ちょうど一致で買い時にならないのは仕様違反。
    // 突然変異テスト (mutation_kotlin.py MU02) で「<= を < に変えても誰も気付かない」
    // ことが分かったため追加した — 境界を踏むフィクスチャが 1 つも無かった。
    "目標価格ちょうどは到達扱い (境界: realPrice == targetPrice)" {
        watchlistBuyVerdict(item(real = 5_000, target = 5_000)) shouldBe
            BuyTimingScorer.Verdict.BUY_NOW
    }

    "目標価格を 1 円上回るときは到達扱いにしない (境界の反対側)" {
        // 追加時価格と同額にして「有意な値下がり」経路が発火しないようにする。
        watchlistBuyVerdict(item(real = 5_001, target = 5_000, added = 5_001)) shouldBe null
    }
})
