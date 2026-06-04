package com.example.popcoon.core

/**
 * 通貨フォーマット。
 *
 * 問題: `"¥${"%,d".format(price)}"` が 11 箇所に散在。
 * → 将来の多通貨対応 (USD/EUR) や小数点処理が困難。
 *
 * 解決: 1 箇所に集約。全 UI はこの関数を呼ぶ。
 */
object CurrencyFormatter {

    /** 日本円フォーマット (例: "¥1,234") */
    fun yen(amount: Long): String = "¥${"%,d".format(amount)}"

    /** 日本円フォーマット + 「円」付き (例: "1,234円") — TalkBack 読み上げ用 */
    fun yenAccessible(amount: Long): String = "${"%,d".format(amount)}円"

    /** 差額表示 (例: "-¥500" or "+¥200") */
    fun yenDiff(diff: Long): String {
        val sign = if (diff >= 0) "+" else ""
        return "$sign¥${"%,d".format(diff)}"
    }

    /** 割引率 (例: "20% OFF") */
    fun discountPercent(original: Long, current: Long): String {
        if (original <= 0) return ""
        val pct = ((original - current) * 100 / original).toInt()
        return if (pct > 0) "${pct}% OFF" else ""
    }

    /** ポイント還元 (例: "+¥100 (1.0%)") */
    fun pointsBack(amount: Long, rateStr: String): String =
        "+¥${"%,d".format(amount)} ($rateStr)"
}
