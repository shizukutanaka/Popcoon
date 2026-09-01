package io.kotest.matchers.doubles

import io.kotest.matchers.Tolerance
import io.kotest.matchers.fail

infix fun Double.plusOrMinus(tolerance: Double): Tolerance = Tolerance(this, tolerance)

infix fun Double.shouldBeGreaterThan(other: Double): Double {
    if (this <= other) fail("expected $this > $other")
    return this
}

infix fun Double.shouldBeGreaterThanOrEqual(other: Double): Double {
    if (this < other) fail("expected $this >= $other")
    return this
}

infix fun Double.shouldBeGreaterThanOrEqualTo(other: Double): Double {
    if (this < other) fail("expected $this >= $other")
    return this
}

infix fun Double.shouldBeLessThan(other: Double): Double {
    if (this >= other) fail("expected $this < $other")
    return this
}

infix fun Double.shouldBeLessThanOrEqual(other: Double): Double {
    if (this > other) fail("expected $this <= $other")
    return this
}

infix fun Double.shouldBeLessThanOrEqualTo(other: Double): Double {
    if (this > other) fail("expected $this <= $other")
    return this
}
