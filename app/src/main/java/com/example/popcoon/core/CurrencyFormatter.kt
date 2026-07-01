package com.example.popcoon.core

import java.util.Locale
import kotlin.math.abs

/**
 * 通貨フォーマット。
 *
 * 問題: `"¥${"%,d".format(price)}"` が 11 箇所に散在。
 * → 将来の多通貨対応 (USD/EUR) や小数点処理が困難。
 *
 * 解決: 1 箇所に集約。全 UI はこの関数を呼ぶ。
 * Locale.US を明示して EU ロケール端末での小数点/桁区切り反転を防ぐ。
 */
object CurrencyFormatter {

    /** 日本円フォーマット (例: "¥1,234") */
    fun yen(amount: Long): String = "¥${String.format(Locale.US, "%,d", amount)}"

    /** 日本円フォーマット + 「円」付き (例: "1,234円") — TalkBack 読み上げ用 */
    fun yenAccessible(amount: Long): String = "${String.format(Locale.US, "%,d", amount)}円"

    /** 差額表示 (例: "-¥300" or "+¥200") */
    fun yenDiff(diff: Long): String {
        val sign = if (diff >= 0) "+" else "-"
        return "$sign¥${String.format(Locale.US, "%,d", abs(diff))}"
    }

    /**
     * 割引率 (例: "20% OFF")
     *
     * (original - current) * 100 を Long のまま計算すると、両者が Long.MAX_VALUE 近くの
     * 極端な値の場合にオーバーフローして符号が反転しうる (円価格では非現実的だが、
     * 将来の多通貨対応や不正な外部データ流入に備えて Double 計算で桁あふれを避ける)。
     */
    fun discountPercent(original: Long, current: Long): String {
        if (original <= 0) return ""
        val pct = ((original - current).toDouble() * 100.0 / original.toDouble()).toInt()
        return if (pct > 0) "${pct}% OFF" else ""
    }

    /** ポイント還元 (例: "+¥100 (1.0%)") */
    fun pointsBack(amount: Long, rateStr: String): String =
        "+¥${String.format(Locale.US, "%,d", amount)} ($rateStr)"
}
