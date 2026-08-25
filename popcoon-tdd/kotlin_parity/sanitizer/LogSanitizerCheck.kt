package io.github.shizukutanaka.popcoon.core

import java.io.File

/**
 * LogSanitizer を **実行して** 共有コーパスと照合する。
 *
 * 期待値は `corpus.tsv` にあり、正規表現から手導出したもの (実装の出力ではない)。
 * 同じコーパスを backend の `test/sanitizer-corpus.test.ts` が TypeScript 実装
 * (`sanitizePii`) に対して回すので、**2 言語が同一規則であること**が
 * fixture drift 無しに検証される。
 *
 * 併せて冪等性 (sanitize(sanitize(x)) == sanitize(x)) を全ケースで検査する。
 * backend は「サニタイズしても変わらない = PII 無し」で二重チェックするため、
 * 冪等でないと正当なクラッシュレポートが 400 で全拒否される。
 */
fun main(args: Array<String>) {
    val corpus = File(args[0])
    var ok = 0
    var fail = 0

    for (raw in corpus.readLines()) {
        if (raw.isBlank() || raw.startsWith("#")) continue
        val parts = raw.split("\t")
        require(parts.size >= 2) { "corpus 行の列が足りない: $raw" }
        val input = parts[0]
        val expected = parts[1]

        val got = LogSanitizer.sanitize(input)
        if (got == expected) {
            ok++
        } else {
            fail++
            println("MISMATCH input=<$input>")
            println("  kotlin  =<$got>")
            println("  expected=<$expected>")
        }

        val twice = LogSanitizer.sanitize(got)
        if (twice != got) {
            fail++
            println("NOT IDEMPOTENT input=<$input>")
            println("  once =<$got>")
            println("  twice=<$twice>")
        } else {
            ok++
        }
    }

    println("LOG SANITIZER: $ok checks passed, $fail failed")
    if (fail > 0 || ok == 0) System.exit(1)
}
