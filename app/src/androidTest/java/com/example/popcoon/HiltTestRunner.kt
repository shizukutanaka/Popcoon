package com.example.popcoon

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Hilt instrumentation tests を動作させるための AndroidJUnitRunner。
 *
 * AndroidManifest でこのクラスを指定することで、テスト実行時に
 * PopcoonApp (HiltAndroidApp) ではなく HiltTestApplication を使う。
 *
 * build.gradle.kts で参照:
 *   testInstrumentationRunner = "com.example.popcoon.HiltTestRunner"
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?,
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
