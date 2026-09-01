package io.kotest.matchers

class AssertionFailed(message: String) : AssertionError(message)

fun fail(message: String): Nothing = throw AssertionFailed(message)

private fun render(v: Any?): String = when (v) {
    null -> "null"
    is String -> "\"$v\""
    else -> v.toString()
}

infix fun <T> T.shouldBe(expected: T): T {
    // kotest は数値型をまたいだ比較も等価とみなさない。ここも同じく厳密比較。
    val eq = when {
        this is Array<*> && expected is Array<*> -> this.contentEquals(expected)
        else -> this == expected
    }
    if (!eq) fail("expected: ${render(expected)}\nbut was : ${render(this)}")
    return this
}

infix fun <T> T.shouldNotBe(expected: T): T {
    if (this == expected) fail("expected not to be: ${render(expected)}")
    return this
}

/** `x shouldBe (y plusOrMinus 0.01)` 用の許容誤差付き期待値。 */
data class Tolerance(val expected: Double, val tolerance: Double)

infix fun Double.shouldBe(t: Tolerance): Double {
    if (kotlin.math.abs(this - t.expected) > t.tolerance) {
        fail("expected: ${t.expected} ± ${t.tolerance}\nbut was : $this")
    }
    return this
}
