package io.kotest.runner

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.ITERATIONS
import io.kotest.property.SEED

/**
 * シム上で StringSpec を実行するランナー。
 *
 * 実行対象は run_kotest.sh が生成する `GeneratedSpecList.kt` が持つ。
 * 何件走ったかを必ず表示する — 「テストがある」と「テストが通った」の混同を避けるため、
 * 除外した spec 数はスクリプト側が別途表示する。
 */
fun runSpecs(specs: List<Pair<String, () -> StringSpec>>): Int {
    var passed = 0
    var failed = 0
    val failures = mutableListOf<String>()

    for ((name, factory) in specs) {
        val spec = try {
            factory()
        } catch (e: Throwable) {
            failed++
            failures += "$name <spec の生成に失敗>: ${e::class.simpleName}: ${e.message}"
            continue
        }
        val tests = try {
            spec.collect()
        } catch (e: Throwable) {
            failed++
            failures += "$name <テストの登録に失敗>: ${e::class.simpleName}: ${e.message}"
            continue
        }
        if (tests.isEmpty()) {
            failed++
            failures += "$name <テストが 1 件も登録されていない>"
            continue
        }
        for ((testName, body) in tests) {
            try {
                body()
                passed++
            } catch (e: Throwable) {
                failed++
                failures += "$name > $testName\n    ${(e.message ?: e.toString()).replace("\n", "\n    ")}"
            }
        }
    }

    println("KOTEST SHIM: ${specs.size} specs / $passed passed / $failed failed " +
        "(property: $ITERATIONS iterations, seed $SEED)")
    if (failures.isNotEmpty()) {
        System.err.println("FAILURES:")
        for (f in failures) System.err.println("  $f")
    }
    return if (failed > 0) 1 else 0
}
