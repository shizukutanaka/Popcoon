package com.example.popcoon.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Critical User Journey (CUJ) を Baseline Profile として記録。
 *
 * 業界実績:
 *  - Android Calendar チーム: cold start 20% 短縮、jank 50% 削減
 *  - JetSnack (Google サンプル): 同等の改善
 *
 * Popcoon の 2 つの主要 CUJ:
 *  1. アプリ起動 → 検索画面表示 (最頻 fresh install ジャーニー)
 *  2. 検索 → 結果一覧 → 商品詳細 (主要 conversion ジャーニー)
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule val rule = BaselineProfileRule()

    private val packageName = "com.example.popcoon"

    @Test
    fun startup() {
        rule.collect(
            packageName = packageName,
            includeInStartupProfile = true,
        ) {
            startActivityAndWait()
            // Compose の reportFullyDrawn() を待つ
            device.wait(Until.hasObject(By.text("商品を検索してください")), 5_000)
        }
    }

    @Test
    fun startupAndSearch() {
        rule.collect(packageName = packageName) {
            startActivityAndWait()

            // 検索バーに入力
            val searchBar = device.findObject(By.clazz("android.widget.EditText"))
                ?: device.findObject(By.descContains("商品名"))
            searchBar?.text = "プリンター"

            // debounce 待ち + 結果ロード待ち (タイムアウト保護)
            Thread.sleep(800)

            // 最初の検索結果をタップ
            device.wait(
                Until.hasObject(By.textContains("¥")), 8_000,
            )?.let { found ->
                if (found) {
                    device.findObject(By.textContains("¥"))?.click()
                    device.wait(Until.hasObject(By.textContains("買い時スコア")), 5_000)
                }
            }
        }
    }
}
