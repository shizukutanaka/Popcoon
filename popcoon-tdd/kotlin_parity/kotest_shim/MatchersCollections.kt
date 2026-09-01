package io.kotest.matchers.collections

import io.kotest.matchers.fail

fun <T> Collection<T>.shouldBeEmpty(): Collection<T> {
    if (isNotEmpty()) fail("expected empty collection but had $size element(s): $this")
    return this
}

fun <T> Collection<T>.shouldNotBeEmpty(): Collection<T> {
    if (isEmpty()) fail("expected non-empty collection")
    return this
}

infix fun <T> Collection<T>.shouldContain(element: T): Collection<T> {
    if (element !in this) fail("expected to contain $element but was: $this")
    return this
}

infix fun <T> Collection<T>.shouldNotContain(element: T): Collection<T> {
    if (element in this) fail("expected NOT to contain $element but was: $this")
    return this
}

infix fun <T> Collection<T>.shouldHaveSize(n: Int): Collection<T> {
    if (size != n) fail("expected size $n but was $size: $this")
    return this
}

infix fun <T> Collection<T>.shouldHaveAtMostSize(n: Int): Collection<T> {
    if (size > n) fail("expected size <= $n but was $size: $this")
    return this
}

infix fun <T> Collection<T>.shouldContainOnly(elements: Collection<T>): Collection<T> {
    val extra = this.filter { it !in elements }
    if (extra.isNotEmpty()) fail("expected only $elements but also had $extra")
    return this
}

fun <T> Collection<T>.shouldContainOnly(vararg elements: T): Collection<T> =
    shouldContainOnly(elements.toList())

infix fun <T> Collection<T>.shouldContainExactlyInAnyOrder(elements: Collection<T>): Collection<T> {
    val a = this.groupingBy { it }.eachCount()
    val b = elements.groupingBy { it }.eachCount()
    if (a != b) fail("expected exactly (any order) $elements but was: $this")
    return this
}

fun <T> Collection<T>.shouldContainExactlyInAnyOrder(vararg elements: T): Collection<T> =
    shouldContainExactlyInAnyOrder(elements.toList())

infix fun <T> Collection<T>.shouldExist(predicate: (T) -> Boolean): Collection<T> {
    if (none(predicate)) fail("expected at least one matching element but none did: $this")
    return this
}

infix fun <T> Collection<T>.shouldNotExist(predicate: (T) -> Boolean): Collection<T> {
    val hit = firstOrNull(predicate)
    if (hit != null) fail("expected no matching element but found: $hit")
    return this
}
