package com.example.popcoon.feature.notification

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * LocalNotificationManager の純関数テスト。
 *
 * 本番コード (LocalNotificationManager.Companion.*) を直接呼ぶことで
 * フォーマットやリンク形式の変更を確実に検出する。
 * NotificationManager 本体は Context 依存なので Instrumentation テストに委ねる。
 */
class NotificationLogicTest : StringSpec({

    "異なる productKey は異なる notification ID" {
        LocalNotificationManager.notificationId("amazon:B0TEST001") shouldNotBe
            LocalNotificationManager.notificationId("rakuten:shop:item-123")
    }

    "同じ productKey は同じ notification ID (更新で上書き)" {
        val key = "amazon:B0SAME"
        LocalNotificationManager.notificationId(key) shouldBe
            LocalNotificationManager.notificationId(key)
    }

    "値下がりテキスト: 5000→4000 の形式確認" {
        LocalNotificationManager.priceAlertText(4000L, 5000L) shouldBe
            "¥4,000 (前回: ¥5,000)"
    }

    "値下がりテキスト: 3桁区切り" {
        LocalNotificationManager.priceAlertText(99_800L, 120_000L) shouldBe
            "¥99,800 (前回: ¥120,000)"
    }

    "Deep Link URI 形式: popcoon://product/{productKey}" {
        LocalNotificationManager.deepLinkUri("amazon:B0TEST001") shouldBe
            "popcoon://product/amazon:B0TEST001"
    }

    "Deep Link URI: platform:sku 形式を維持" {
        LocalNotificationManager.deepLinkUri("rakuten:shop:item-123") shouldBe
            "popcoon://product/rakuten:shop:item-123"
    }
})
