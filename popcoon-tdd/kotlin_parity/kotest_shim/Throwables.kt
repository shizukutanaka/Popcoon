package io.kotest.assertions.throwables

import io.kotest.matchers.AssertionFailed
import io.kotest.matchers.fail

inline fun <reified T : Throwable> shouldThrow(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        // アサーション失敗を「期待した例外」として飲み込まない。
        if (e is AssertionFailed) throw e
        fail("expected ${T::class.simpleName} but threw ${e::class.simpleName}: ${e.message}")
    }
    fail("expected ${T::class.simpleName} but nothing was thrown")
}

fun shouldNotThrowAny(block: () -> Unit) {
    try {
        block()
    } catch (e: AssertionFailed) {
        throw e
    } catch (e: Throwable) {
        fail("expected no exception but threw ${e::class.simpleName}: ${e.message}")
    }
}
