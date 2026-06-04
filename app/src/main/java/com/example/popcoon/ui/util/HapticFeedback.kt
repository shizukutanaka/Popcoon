package com.example.popcoon.ui.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build

/**
 * 触覚フィードバック。
 *
 * Apple HIG より:
 *  - すべての操作には即時フィードバックが必要
 *  - 成功・失敗・警告は触覚で区別できる
 *  - 「重要な操作には重めのフィードバック」
 *
 * Android 実装:
 *  - EFFECT_CLICK: 軽い確認 (ボタンタップ)
 *  - EFFECT_HEAVY_CLICK: 重要な操作 (購入、削除確定)
 *  - EFFECT_DOUBLE_CLICK: 完了 (バーコード読み取り成功、ウォッチリスト追加)
 *  - カスタムパターン: 警告 (ダークパターン検出)
 */
object HapticFeedback {

    fun light(context: Context) = vibrate(
        context, VibrationEffect.EFFECT_CLICK
    )

    fun success(context: Context) = vibrate(
        context, VibrationEffect.EFFECT_DOUBLE_CLICK
    )

    fun heavy(context: Context) = vibrate(
        context, VibrationEffect.EFFECT_HEAVY_CLICK
    )

    /** ダークパターン検出時の警告バイブ */
    fun warning(context: Context) {
        val vib = vibrator(context) ?: return
        val pattern = VibrationEffect.createWaveform(
            longArrayOf(0L, 100L, 50L, 100L),  // delay, on, off, on
            intArrayOf(0, 180, 0, 180),          // amplitude
            -1,                                   // 繰り返しなし
        )
        vib.vibrate(pattern)
    }

    private fun vibrate(context: Context, effectId: Int) {
        val vib = vibrator(context) ?: return
        runCatching { vib.vibrate(VibrationEffect.createPredefined(effectId)) }
    }

    private fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
