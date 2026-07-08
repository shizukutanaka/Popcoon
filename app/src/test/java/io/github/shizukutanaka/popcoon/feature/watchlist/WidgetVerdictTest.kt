package io.github.shizukutanaka.popcoon.feature.watchlist

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * WidgetVerdict — ウィジェット用の軽量買い時判定の純関数テスト。
 * 文字列は PopcoonWidget が解釈する verdict キー (BUY_NOW/WAIT/NEUTRAL) と一致。
 */
class WidgetVerdictTest : StringSpec({

    "目標価格に到達 → BUY_NOW" {
        WidgetVerdict.forItem(realPrice = 4000, targetPrice = 4000, addedPrice = 5000) shouldBe
            WidgetVerdict.BUY_NOW
    }

    "目標価格を下回る → BUY_NOW" {
        WidgetVerdict.forItem(realPrice = 3500, targetPrice = 4000, addedPrice = 5000) shouldBe
            WidgetVerdict.BUY_NOW
    }

    "目標は最優先: 追加時から上昇していても到達なら BUY_NOW" {
        // 追加 3000 → 現在 3800 (上昇) だが目標 4000 以下 → BUY_NOW
        WidgetVerdict.forItem(realPrice = 3800, targetPrice = 4000, addedPrice = 3000) shouldBe
            WidgetVerdict.BUY_NOW
    }

    "目標未設定 + 追加時から 5% 下落 → BUY_NOW" {
        // 1000 → 950 = -5%
        WidgetVerdict.forItem(realPrice = 950, targetPrice = null, addedPrice = 1000) shouldBe
            WidgetVerdict.BUY_NOW
    }

    "目標未設定 + 追加時から 5% 上昇 → WAIT" {
        // 1000 → 1050 = +5%
        WidgetVerdict.forItem(realPrice = 1050, targetPrice = null, addedPrice = 1000) shouldBe
            WidgetVerdict.WAIT
    }

    "目標未設定 + 小幅変動 (4%) → NEUTRAL" {
        // 1000 → 960 = -4% (整数 floor で閾値未満)
        WidgetVerdict.forItem(realPrice = 960, targetPrice = null, addedPrice = 1000) shouldBe
            WidgetVerdict.NEUTRAL
    }

    "追加時価格 0 (基準なし) + 目標なし → NEUTRAL" {
        WidgetVerdict.forItem(realPrice = 1000, targetPrice = null, addedPrice = 0) shouldBe
            WidgetVerdict.NEUTRAL
    }

    "現在価格 0 以下 → NEUTRAL (判定不能)" {
        WidgetVerdict.forItem(realPrice = 0, targetPrice = 500, addedPrice = 1000) shouldBe
            WidgetVerdict.NEUTRAL
    }

    "目標価格 0 以下は未設定扱い → 値動きで判定" {
        // target=0 は無効 → 追加時から -10% 下落 → BUY_NOW
        WidgetVerdict.forItem(realPrice = 900, targetPrice = 0, addedPrice = 1000) shouldBe
            WidgetVerdict.BUY_NOW
    }

    // 識別: 上昇側の境界 (4% は NEUTRAL、5% は WAIT) が対称に正しく実装されているか
    // 旧テストは -4% NEUTRAL のみ。+4% NEUTRAL がなければ上昇側 SIGNIFICANT_MOVE_PERCENT が
    // 誤った閾値 (3% 等) でも旧テストが緑になる。
    "追加時から +4% (閾値未満) → NEUTRAL (上昇側境界識別)" {
        // 1000 → 1040 = +4% → NEUTRAL (threshold=5 未満)
        WidgetVerdict.forItem(realPrice = 1040, targetPrice = null, addedPrice = 1000) shouldBe
            WidgetVerdict.NEUTRAL
    }
})
