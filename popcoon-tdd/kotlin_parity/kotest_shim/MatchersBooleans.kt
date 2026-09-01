package io.kotest.matchers.booleans

import io.kotest.matchers.fail

fun Boolean.shouldBeTrue(): Boolean {
    if (!this) fail("expected true but was false")
    return this
}

fun Boolean.shouldBeFalse(): Boolean {
    if (this) fail("expected false but was true")
    return this
}
