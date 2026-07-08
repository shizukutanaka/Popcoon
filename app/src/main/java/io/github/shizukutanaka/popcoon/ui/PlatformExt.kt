package io.github.shizukutanaka.popcoon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.data.model.Platform

/**
 * UI レイヤでプラットフォーム名をロケール対応で表示する。
 *
 * `Platform.displayName` は data 層の定数 ("Amazon" / "楽天" / "Yahoo!") で日本語固定のため、
 * 英語/韓国語/中国語 UI でも「楽天」がそのまま出てしまう。Compose では `stringResource` を
 * 使って端末ロケールの platform_* を引く。表示専用 (シリアライズ/ロジックは displayName を使う)。
 */
@Composable
fun Platform.localizedName(): String = when (this) {
    Platform.AMAZON -> stringResource(R.string.platform_amazon)
    Platform.RAKUTEN -> stringResource(R.string.platform_rakuten)
    Platform.YAHOO -> stringResource(R.string.platform_yahoo)
}
