package io.kotest.property.arbitrary

import io.kotest.property.Arb
import io.kotest.property.Gen
import java.util.Random

private fun Random.nextLongIn(range: LongRange): Long {
    val span = range.last - range.first
    if (span <= 0) return range.first
    // span+1 が溢れない範囲で一様に取る
    return range.first + (Math.floorMod(nextLong(), span + 1))
}

fun Arb.long(range: LongRange): Gen<Long> = Gen { rnd -> rnd.nextLongIn(range) }

fun Arb.long(): Gen<Long> = long(Long.MIN_VALUE / 4..Long.MAX_VALUE / 4)

fun Arb.int(range: IntRange): Gen<Int> =
    Gen { rnd -> range.first + rnd.nextInt(range.last - range.first + 1) }

fun Arb.int(): Gen<Int> = int(Int.MIN_VALUE / 4..Int.MAX_VALUE / 4)

fun Arb.float(range: ClosedFloatingPointRange<Float>): Gen<Float> =
    Gen { rnd -> range.start + rnd.nextFloat() * (range.endInclusive - range.start) }

fun Arb.float(): Gen<Float> = float(-1e6f..1e6f)

fun Arb.double(range: ClosedFloatingPointRange<Double>): Gen<Double> =
    Gen { rnd -> range.start + rnd.nextDouble() * (range.endInclusive - range.start) }

// 生成文字の範囲: ASCII 記号・英数に加えて全角/日本語を混ぜる (本番の商品タイトルに近づける)
private val CHARS: List<Char> =
    (' '..'~').toList() + "０１２３４５６７８９あア亜ｱ㌘・—　\t\n".toList()

private fun Random.string(len: Int): String =
    (0 until len).map { CHARS[nextInt(CHARS.size)] }.joinToString("")

fun Arb.string(range: IntRange): Gen<String> =
    Gen { rnd -> rnd.string(range.first + rnd.nextInt(range.last - range.first + 1)) }

fun Arb.string(minSize: Int, maxSize: Int): Gen<String> = string(minSize..maxSize)

fun Arb.string(): Gen<String> = string(0..40)

fun <T> Arb.list(gen: Gen<T>, range: IntRange): Gen<List<T>> = Gen { rnd ->
    val n = range.first + rnd.nextInt(range.last - range.first + 1)
    (0 until n).map { gen.sample(rnd) }
}

fun <T> Arb.list(gen: Gen<T>): Gen<List<T>> = list(gen, 0..20)

inline fun <reified T : Enum<T>> Arb.enum(): Gen<T> {
    val values = enumValues<T>()
    return Gen { rnd -> values[rnd.nextInt(values.size)] }
}

fun <T> Arb.element(values: Collection<T>): Gen<T> {
    val list = values.toList()
    return Gen { rnd -> list[rnd.nextInt(list.size)] }
}

fun <T> Arb.element(vararg values: T): Gen<T> = element(values.toList())

/** 単発生成 (kotest の `Arb.next()` 相当)。 */
fun <T> Gen<T>.next(): T = sample(Arb.random)
