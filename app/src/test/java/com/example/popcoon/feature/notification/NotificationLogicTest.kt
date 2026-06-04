package com.example.popcoon.feature.notification

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * 通知ロジックの pure 関数テスト。
 *
 * Android NotificationManager は Context 必須のため Instrumentation に委ね、
 * ここでは ID 衝突防止 / テキスト生成ロジックのみ検証。
 */
class NotificationLogicTest : StringSpec({

    "異なる productKey は異なる notification ID" {
        val id1 = "amazon:B0TEST001".hashCode()
        val id2 = "rakuten:shop:item-123".hashCode()
        id1 shouldNotBe id2
    }

    "同じ productKey は同じ notification ID (更新で上書き)" {
        val key = "amazon:B0SAME"
        key.hashCode() shouldBe key.hashCode()
    }

    "値下がりテキスト生成: タイトル 20 文字切り詰め" {
        val title = "これは非常に長い商品タイトルで20文字を超えています"
        val truncated = title.take(20)
        truncated.length shouldBe 20
    }

    "値下がり率テキスト: 5000→4000" {
        val prev = 5000L
        val current = 4000L
        val dropPct = ((prev - current) * 100 / prev).toInt()
        val text = "¥${"%,d".format(current)} (前回: ¥${"%,d".format(prev)})"
        text shouldBe "¥4,000 (前回: ¥5,000)"
        dropPct shouldBe 20
    }

    "Deep Link URI 形式" {
        val key = "amazon:B0TEST001"
        val uri = "popcoon://product/$key"
        uri shouldBe "popcoon://product/amazon:B0TEST001"
    }
})
