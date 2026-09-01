package io.kotest.property

import java.util.Random

/**
 * プロパティテストの最小互換シム。
 *
 * kotest 既定の 1000 試行ではなく [ITERATIONS] (既定 300、`POPCOON_PROPERTY_ITERATIONS`
 * で変更可) を **シード固定**で回す。shrinking は行わない — 反例はそのまま表示する。
 * 本物より弱いことを明示するため、ランナーが試行回数とシードを毎回出力する。
 */
object Arb {
    val random: Random = Random(SEED)
}

val SEED: Long = System.getenv("POPCOON_PROPERTY_SEED")?.toLongOrNull() ?: 20260825L

val ITERATIONS: Int = System.getenv("POPCOON_PROPERTY_ITERATIONS")?.toIntOrNull() ?: 300

/** 値を 1 つ生成する生成器。 */
fun interface Gen<T> {
    fun sample(rnd: Random): T
}

private class Counterexample(val args: List<Any?>, cause: Throwable) :
    AssertionError("property failed for ${args.joinToString(", ")}: ${cause.message}", cause)

private inline fun runTrials(body: (Random) -> Unit) {
    val rnd = Random(SEED)
    repeat(ITERATIONS) { body(rnd) }
}

fun <A> checkAll(genA: Gen<A>, block: (A) -> Unit) = runTrials { rnd ->
    val a = genA.sample(rnd)
    try {
        block(a)
    } catch (e: Throwable) {
        throw Counterexample(listOf(a), e)
    }
}

fun <A, B> checkAll(genA: Gen<A>, genB: Gen<B>, block: (A, B) -> Unit) = runTrials { rnd ->
    val a = genA.sample(rnd)
    val b = genB.sample(rnd)
    try {
        block(a, b)
    } catch (e: Throwable) {
        throw Counterexample(listOf(a, b), e)
    }
}

fun <A, B, C> checkAll(genA: Gen<A>, genB: Gen<B>, genC: Gen<C>, block: (A, B, C) -> Unit) =
    runTrials { rnd ->
        val a = genA.sample(rnd)
        val b = genB.sample(rnd)
        val c = genC.sample(rnd)
        try {
            block(a, b, c)
        } catch (e: Throwable) {
            throw Counterexample(listOf(a, b, c), e)
        }
    }
