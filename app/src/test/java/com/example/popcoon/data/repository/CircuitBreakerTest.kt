package com.example.popcoon.data.repository

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CircuitBreakerTest : StringSpec({

    "初期状態は CLOSED でリクエストを許可" {
        val breaker = CircuitBreaker()
        breaker.currentState() shouldBe CircuitBreaker.State.CLOSED
        breaker.allowRequest(0L) shouldBe true
    }

    "閾値未満の失敗では CLOSED のまま" {
        val breaker = CircuitBreaker(failureThreshold = 3)
        breaker.recordFailure(0L)
        breaker.recordFailure(0L)
        breaker.currentState() shouldBe CircuitBreaker.State.CLOSED
        breaker.allowRequest(0L) shouldBe true
    }

    "閾値到達で OPEN に遷移しリクエストを拒否" {
        val breaker = CircuitBreaker(failureThreshold = 3)
        breaker.recordFailure(0L)
        breaker.recordFailure(0L)
        breaker.recordFailure(0L)
        breaker.currentState() shouldBe CircuitBreaker.State.OPEN
        breaker.allowRequest(1_000L) shouldBe false
    }

    "OPEN 中は openDurationMs 経過まで拒否し続ける" {
        val breaker = CircuitBreaker(failureThreshold = 1, openDurationMs = 60_000L)
        breaker.recordFailure(0L)
        breaker.allowRequest(30_000L) shouldBe false
        breaker.allowRequest(59_999L) shouldBe false
    }

    "openDurationMs 経過後は HALF_OPEN として 1 回だけ許可" {
        val breaker = CircuitBreaker(failureThreshold = 1, openDurationMs = 60_000L)
        breaker.recordFailure(0L)
        breaker.allowRequest(60_000L) shouldBe true
        breaker.currentState() shouldBe CircuitBreaker.State.HALF_OPEN
    }

    "HALF_OPEN 中の成功は CLOSED に戻り、以後は連続失敗閾値がリセットされる" {
        val breaker = CircuitBreaker(failureThreshold = 1, openDurationMs = 60_000L)
        breaker.recordFailure(0L)
        breaker.allowRequest(60_000L)
        breaker.recordSuccess()
        breaker.currentState() shouldBe CircuitBreaker.State.CLOSED
        breaker.allowRequest(60_001L) shouldBe true
    }

    "HALF_OPEN 中の失敗は即座に OPEN へ戻す (試行権を使い切る)" {
        val breaker = CircuitBreaker(failureThreshold = 3, openDurationMs = 60_000L)
        breaker.recordFailure(0L)
        breaker.recordFailure(0L)
        breaker.recordFailure(0L)
        breaker.allowRequest(60_000L) // HALF_OPEN へ遷移
        breaker.recordFailure(60_000L)
        breaker.currentState() shouldBe CircuitBreaker.State.OPEN
        breaker.allowRequest(60_000L) shouldBe false
    }

    "recordSuccess は連続失敗カウントをリセットする" {
        val breaker = CircuitBreaker(failureThreshold = 3)
        breaker.recordFailure(0L)
        breaker.recordFailure(0L)
        breaker.recordSuccess()
        breaker.recordFailure(0L)
        breaker.recordFailure(0L)
        // リセットされていれば、あと1回の失敗ではまだ閾値(3)未到達
        breaker.currentState() shouldBe CircuitBreaker.State.CLOSED
    }
})
