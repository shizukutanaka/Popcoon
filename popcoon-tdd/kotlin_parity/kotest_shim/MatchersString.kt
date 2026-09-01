package io.kotest.matchers.string

import io.kotest.matchers.fail

infix fun String?.shouldContain(sub: String): String? {
    if (this == null || !this.contains(sub)) fail("expected to contain \"$sub\" but was: $this")
    return this
}

infix fun String?.shouldNotContain(sub: String): String? {
    if (this != null && this.contains(sub)) fail("expected NOT to contain \"$sub\" but was: $this")
    return this
}

infix fun String?.shouldStartWith(prefix: String): String? {
    if (this == null || !this.startsWith(prefix)) fail("expected to start with \"$prefix\" but was: $this")
    return this
}

fun String?.shouldNotBeEmpty(): String? {
    if (this.isNullOrEmpty()) fail("expected non-empty string but was: $this")
    return this
}
