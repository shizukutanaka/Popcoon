package io.kotest.matchers.longs

import io.kotest.matchers.fail

infix fun Long.shouldBeGreaterThan(other: Long): Long {
    if (this <= other) fail("expected $this > $other")
    return this
}

infix fun Long.shouldBeGreaterThanOrEqualTo(other: Long): Long {
    if (this < other) fail("expected $this >= $other")
    return this
}

infix fun Long.shouldBeLessThan(other: Long): Long {
    if (this >= other) fail("expected $this < $other")
    return this
}

infix fun Long.shouldBeLessThanOrEqualTo(other: Long): Long {
    if (this > other) fail("expected $this <= $other")
    return this
}

infix fun Long.shouldBeInRange(range: LongRange): Long {
    if (this !in range) fail("expected $this in $range")
    return this
}
