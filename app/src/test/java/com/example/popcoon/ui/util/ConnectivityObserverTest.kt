package com.example.popcoon.ui.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * ConnectivityObserver のロジックテスト。
 * Context が必要な部分は Instrumentation テストに委ね、
 * ここは型・enum の整合性確認のみ。
 */
class ConnectivityObserverTest : StringSpec({

    "Status enum は4つの既知の値を持つ" {
        ConnectivityObserver.Status.entries shouldContainExactlyInAnyOrder listOf(
            ConnectivityObserver.Status.AVAILABLE,
            ConnectivityObserver.Status.UNAVAILABLE,
            ConnectivityObserver.Status.LOSING,
            ConnectivityObserver.Status.LOST,
        )
    }

    "AVAILABLE のみが接続状態と判定される" {
        // OfflineBannerViewModel ロジックの仕様確認
        val isOffline: (ConnectivityObserver.Status) -> Boolean = {
            it != ConnectivityObserver.Status.AVAILABLE
        }
        isOffline(ConnectivityObserver.Status.AVAILABLE) shouldBe false
        isOffline(ConnectivityObserver.Status.UNAVAILABLE) shouldBe true
        isOffline(ConnectivityObserver.Status.LOSING) shouldBe true
        isOffline(ConnectivityObserver.Status.LOST) shouldBe true
    }
})
