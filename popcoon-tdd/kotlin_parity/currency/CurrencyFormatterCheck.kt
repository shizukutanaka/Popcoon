package io.github.shizukutanaka.popcoon.core

import java.util.Locale

/**
 * CurrencyFormatter の実行検証ハーネス (Android SDK 不要 — pure: java.util.Locale のみ)。
 * run_currency.sh から CurrencyFormatter.kt と一緒にコンパイル・実行する。
 *
 * 主眼 (回帰防止): CurrencyFormatter は「EU ロケール端末で桁区切りが反転するのを防ぐ」ために
 * Locale.US を明示している。だが従来テストは既定ロケール下でしか走らず、Locale.US 引数を
 * 削除しても気付けなかった (検証の演劇)。本ハーネスは **de_DE ロケール下**で実行し、
 * それでも "1,234" (カンマ) を保つことをアサートして guard を識別的に固定する。
 */
private var fails = 0
private fun expect(name: String, actual: String, want: String) {
    if (actual != want) { println("MISMATCH [$name]: got '$actual' want '$want'"); fails++ }
}

private fun runAssertions(tag: String) {
    expect("$tag yen", CurrencyFormatter.yen(1234), "¥1,234")
    expect("$tag yen 0", CurrencyFormatter.yen(0), "¥0")
    expect("$tag yen 1e6", CurrencyFormatter.yen(1_000_000), "¥1,000,000")
    expect("$tag yenAccessible", CurrencyFormatter.yenAccessible(3980), "3,980円")
    expect("$tag yenDiff +", CurrencyFormatter.yenDiff(500), "+¥500")
    expect("$tag yenDiff -", CurrencyFormatter.yenDiff(-300), "-¥300")
    expect("$tag yenDiff 0", CurrencyFormatter.yenDiff(0), "+¥0")
    expect("$tag discount", CurrencyFormatter.discountPercent(5000, 4000), "20% OFF")
    expect("$tag discount up", CurrencyFormatter.discountPercent(3000, 4000), "")
    expect("$tag discount 0", CurrencyFormatter.discountPercent(0, 1000), "")
    expect("$tag points", CurrencyFormatter.pointsBack(100, "1.0%"), "+¥100 (1.0%)")
    // 7桁で区切りが2つ入るケース — ロケール反転バグが最も顕在化する
    expect("$tag yen 7digit", CurrencyFormatter.yen(1_234_567), "¥1,234,567")
}

fun main() {
    val saved = Locale.getDefault()
    try {
        // 1) 既定 (US 相当) ロケールでの基本契約
        Locale.setDefault(Locale.US)
        runAssertions("US")

        // 2) de_DE ロケール: 既定書式なら "1.234.567" になる。Locale.US guard が効いていれば
        //    依然カンマ区切りを保つ。guard を削除するとこのブロックが落ちる。
        Locale.setDefault(Locale.GERMANY)
        runAssertions("de_DE")

        // 3) アラビア数字以外を使うロケールでも ASCII 数字+カンマを保つこと
        Locale.setDefault(Locale("ar"))  // アラビア語: 既定だと東アラビア数字になり得る
        expect("ar yen", CurrencyFormatter.yen(1_234_567), "¥1,234,567")
    } finally {
        Locale.setDefault(saved)
    }

    if (fails == 0) println("CURRENCY FORMATTER: all assertions passed (locale-independent under de_DE/ar)")
    else { println("CURRENCY FORMATTER: $fails assertion(s) FAILED"); kotlin.system.exitProcess(1) }
}
