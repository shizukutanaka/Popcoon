package io.github.shizukutanaka.popcoon.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * アプリ全体で使用するアイコンを集約。
 *
 * Apple の SF Symbols 相当の方針:
 *  - 1 ユースケース = 1 アイコン (使い回しを徹底)
 *  - 各アイコンに明確な意味論的名前を付与
 *  - 直接 `Icons.Default.Star` を呼ばず `AppIcons.Save` 経由
 *  - 将来的にアイコンセットを差し替える時もここを修正するだけ
 *
 * RTL 自動反転:
 *  - 矢印系は `automirrored` バージョンを採用 (アラビア語等で自動反転)
 */
object AppIcons {

    // ナビゲーション
    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val Home: ImageVector = Icons.Default.Home

    // 検索
    val Search: ImageVector = Icons.Default.Search
    val Barcode: ImageVector = Icons.Default.PhotoCamera
    val Calendar: ImageVector = Icons.Default.DateRange

    // 操作
    val Save: ImageVector = Icons.Default.Star
    val Unsave: ImageVector = Icons.Default.StarBorder
    val Share: ImageVector = Icons.Default.Share
    val Settings: ImageVector = Icons.Default.Settings

    // 状態表示
    val Offline: ImageVector = Icons.Default.WifiOff
    val History: ImageVector = Icons.Default.History
    /**
     * 商品画像が無い/読み込み失敗時のフォールバック。
     * 以前は EC プラットフォームごとの絵文字 (📦🛒🟡) を使っており、
     * 実際のブランドと乖離した誤解を招く表現だった (例: 楽天=🛒はどの EC でも
     * 使われうる汎用アイコンで実際のブランドを表さない、商用リリース監査で発見)。
     * プラットフォーム名自体は隣接する PlatformChip (テキスト表示) が既に正しく示すため、
     * ここでは中立的な「画像なし」アイコンにする。
     */
    val ImagePlaceholder: ImageVector = Icons.Default.Image

    // 一覧操作
    val Sort: ImageVector = Icons.AutoMirrored.Filled.Sort
    val Check: ImageVector = Icons.Default.Check
    val Close: ImageVector = Icons.Default.Close
}
