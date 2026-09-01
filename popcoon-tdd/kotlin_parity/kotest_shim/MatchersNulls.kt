package io.kotest.matchers.nulls

import io.kotest.matchers.fail
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

fun <T> T?.shouldBeNull() {
    if (this != null) fail("expected null but was: $this")
}

@OptIn(ExperimentalContracts::class)
fun <T : Any> T?.shouldNotBeNull(): T {
    contract { returns() implies (this@shouldNotBeNull != null) }
    if (this == null) fail("expected non-null but was null")
    return this
}
