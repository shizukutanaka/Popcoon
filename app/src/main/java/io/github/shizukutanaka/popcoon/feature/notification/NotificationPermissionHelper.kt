package io.github.shizukutanaka.popcoon.feature.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat

/**
 * Android 13+ (API 33) の POST_NOTIFICATIONS 権限管理。
 *
 * 戦略:
 *  - 起動時に自動ポップアップしない (UX を阻害しない)
 *  - 「価格アラートを設定」ボタンタップ時に初回リクエスト
 *  - 拒否されたら通知不要モード (アプリ内バッジで代替)
 *
 * Apple HIG 準拠:
 *  - 権限要求の前に「なぜ必要か」のコンテキストを示す
 *  - 拒否されても主機能を維持する
 */
object NotificationPermissionHelper {

    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Compose 用の通知権限リクエスト Composable。
 *
 * 使用例:
 * ```
 * RequestNotificationPermission(shouldRequest = registerAlertRequested) { granted ->
 *     if (granted) viewModel.confirmAlert()
 * }
 * ```
 */
@Composable
fun RequestNotificationPermission(
    shouldRequest: Boolean,
    onResult: (Boolean) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onResult(granted)
    }

    LaunchedEffect(shouldRequest) {
        if (shouldRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (shouldRequest) {
            // Android 12 以下は自動付与
            onResult(true)
        }
    }
}
