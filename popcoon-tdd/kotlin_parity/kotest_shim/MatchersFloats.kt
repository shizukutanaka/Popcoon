package io.kotest.matchers.floats

import io.kotest.matchers.fail

infix fun Float.shouldBeGreaterThanOrEqual(other: Float): Float {
    if (this < other) fail("expected $this >= $other")
    return this
}

infix fun Float.shouldBeLessThanOrEqual(other: Float): Float {
    if (this > other) fail("expected $this <= $other")
    return this
}
