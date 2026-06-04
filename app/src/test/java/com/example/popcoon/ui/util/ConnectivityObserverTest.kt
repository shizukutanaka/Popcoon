package com.example.popcoon.ui.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * ConnectivityObserver のロジックテスト。
 * Context が必要な部分は Instrumentation テストに委ね、
 * ここは型・enum の整合性確認のみ。
 */
class ConnectivityObserverTest : StringSpec({

    "Status enum は4つの値を持つ" {
        ConnectivityObserver.Status.entries.size shouldBe 4
        ConnectivityObserver.Status.AVAILABLE shouldNotBe null
        ConnectivityObserver.Status.UNAVAILABLE shouldNotBe null
        ConnectivityObserver.Status.LOSING shouldNotBe null
        ConnectivityObserver.Status.LOST shouldNotBe null
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
