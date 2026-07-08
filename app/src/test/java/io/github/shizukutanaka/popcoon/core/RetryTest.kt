package io.github.shizukutanaka.popcoon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * retryOnce() のテスト。
 *
 * 検索クライアント (Amazon/Rakuten/Yahoo) は単発失敗時に即座に空リストへフォールバックしており、
 * 一時的なネットワーク瞬断でもリトライされていなかった (商用リリース監査で発見)。
 */
class RetryTest : StringSpec({

    "1回目で成功すればそのまま結果を返す (リトライ不要)" {
        runTest {
            var callCount = 0
            val result = retryOnce {
                callCount++
                "ok"
            }
            result shouldBe "ok"
            callCount shouldBe 1
        }
    }

    "1回目が失敗し2回目で成功すればリトライ結果を返す" {
        runTest {
            var callCount = 0
            val result = retryOnce {
                callCount++
                if (callCount == 1) throw java.io.IOException("transient")
                "recovered"
            }
            result shouldBe "recovered"
            callCount shouldBe 2
        }
    }

    "2回とも失敗すれば最後の例外がそのまま伝播する" {
        runTest {
            var callCount = 0
            try {
                retryOnce {
                    callCount++
                    throw java.io.IOException("persistent failure")
                }
                error("例外が伝播しなかった")
            } catch (e: java.io.IOException) {
                e.message shouldBe "persistent failure"
            }
            callCount shouldBe 2
        }
    }

    "CancellationException はリトライせず即座に再 throw する" {
        runTest {
            var callCount = 0
            try {
                retryOnce {
                    callCount++
                    throw CancellationException("cancelled")
                }
                error("CancellationException が伝播しなかった")
            } catch (e: CancellationException) {
                // 期待通り: リトライされていないこと (呼び出しは1回のみ)
            }
            callCount shouldBe 1
        }
    }
})
