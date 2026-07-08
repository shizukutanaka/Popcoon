package io.github.shizukutanaka.popcoon.feature.crash

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class StartupTrackerTest : StringSpec({

    val tracker = StartupTracker()

    fun metrics(ms: Long) = StartupTracker.StartupMetrics(
        totalDurationMs = ms,
        launchedFromForegroundProcess = false,
        startType = "cold",
    )

    "SLOW_THRESHOLD_MS は 1500" {
        StartupTracker.SLOW_THRESHOLD_MS shouldBe 1500L
    }

    "1499ms は遅くない" {
        tracker.isStartupSlow(metrics(1499L)) shouldBe false
    }

    "1500ms は境界値: 超えていないので遅くない" {
        tracker.isStartupSlow(metrics(1500L)) shouldBe false
    }

    "1501ms は遅い" {
        tracker.isStartupSlow(metrics(1501L)) shouldBe true
    }

    "0ms は遅くない" {
        tracker.isStartupSlow(metrics(0L)) shouldBe false
    }
})
