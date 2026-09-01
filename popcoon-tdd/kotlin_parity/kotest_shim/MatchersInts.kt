package io.kotest.matchers.ints

import io.kotest.matchers.fail

infix fun Int.shouldBeGreaterThan(other: Int): Int {
    if (this <= other) fail("expected $this > $other")
    return this
}

infix fun Int.shouldBeGreaterThanOrEqualTo(other: Int): Int {
    if (this < other) fail("expected $this >= $other")
    return this
}

infix fun Int.shouldBeLessThan(other: Int): Int {
    if (this >= other) fail("expected $this < $other")
    return this
}

infix fun Int.shouldBeLessThanOrEqualTo(other: Int): Int {
    if (this > other) fail("expected $this <= $other")
    return this
}

infix fun Int.shouldBeInRange(range: IntRange): Int {
    if (this !in range) fail("expected $this in $range")
    return this
}
