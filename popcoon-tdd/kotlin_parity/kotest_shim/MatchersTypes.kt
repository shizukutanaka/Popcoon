package io.kotest.matchers.types

import io.kotest.matchers.fail

inline fun <reified T : Any> Any?.shouldBeInstanceOf(): T {
    if (this !is T) fail("expected ${T::class.simpleName} but was ${this?.let { it::class.simpleName }}")
    return this
}
