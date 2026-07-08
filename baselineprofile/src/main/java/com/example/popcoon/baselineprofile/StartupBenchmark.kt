package io.github.shizukutanaka.popcoon.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start 計測ベンチマーク。
 *
 * 期待値 (推定):
 *  - CompilationMode.None:                ~1500ms (worst case)
 *  - CompilationMode.Partial(Baseline):   ~900ms  (Calendar チームと同等改善 = 30-40%)
 *
 * CI で 10 イテレーションし中央値で評価。サブ秒 startup を維持する閾値検証。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    private val packageName = "io.github.shizukutanaka.popcoon"

    @Test
    fun startupCompilationNone() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = startup(
        CompilationMode.Partial(BaselineProfileMode.Require)
    )

    @Test
    fun startupFullCompilation() = startup(CompilationMode.Full())

    private fun startup(mode: CompilationMode) {
        rule.measureRepeated(
            packageName = packageName,
            metrics = listOf(StartupTimingMetric()),
            iterations = 10,
            startupMode = StartupMode.COLD,
            compilationMode = mode,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
    }
}
