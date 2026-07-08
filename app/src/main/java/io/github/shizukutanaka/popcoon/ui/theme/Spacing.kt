package io.github.shizukutanaka.popcoon.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Apple iOS の 4pt グリッドシステム準拠。
 *
 * 「全ての要素間距離は 4dp の倍数にする」が iOS の鉄則。
 * これによりレイアウトが視覚的に整い、リズム感が生まれる。
 *
 * 使用例:
 *  - Spacer(Modifier.height(Spacing.md))     // 8dp
 *  - Modifier.padding(Spacing.lg)             // 16dp
 *  - Arrangement.spacedBy(Spacing.sm)         // 4dp
 *
 * Material Design の 8dp グリッドより細かい (4dp は iOS 推奨)
 */
object Spacing {
    /** 2dp — 最小間隔 */
    val xxs = 2.dp

    /** 4dp — 最小単位 */
    val sm = 4.dp

    /** 6dp — コンパクト */
    val xs = 6.dp

    /** 8dp — 関連要素グループ内 */
    val md = 8.dp

    /** 12dp — リスト項目パディング */
    val ml = 12.dp

    /** 16dp — カード内パディング (iOS 標準) */
    val lg = 16.dp

    /** 20dp — Apple Modal 標準 */
    val xl = 20.dp

    /** 24dp — 画面端パディング */
    val xxl = 24.dp

    /** 32dp — 大セクション間 */
    val xxxl = 32.dp

    /** チャート・グラフ要素の標準高さ */
    val chart = 160.dp
}

/**
 * アイコンサイズ標準値。
 * Apple SF Symbols と同じ名称体系で統一。
 */
object IconSize {
    val sm = 16.dp    // OfflineBanner, hint icons
    val md = 18.dp    // SearchSuggestions
    val lg = 24.dp    // NavigationBar icons
    val xl = 32.dp    // Feature badges
}

/**
 * 角丸の Apple 標準値。
 *
 *  - 6dp: タグ / バッジ
 *  - 12dp: カード (Apple iOS の標準)
 *  - 16dp: モーダル / 大カード
 *  - 24dp: ボトムシート (iOS Bottom Sheet)
 *  - 999dp: ピル形ボタン
 */
object CornerRadius {
    val tag = 6.dp
    val card = 12.dp
    val modal = 16.dp
    val sheet = 24.dp
    val pill = 999.dp
}

/**
 * Apple 標準のタッチターゲット最小サイズ。
 *
 * Apple HIG: 44pt × 44pt が最小 (iOS)
 * Material 3: 48dp が最小
 * → Popcoon は最大公約数の 48dp を採用
 */
object TouchTarget {
    val min = 48.dp
}
