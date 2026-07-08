package io.github.shizukutanaka.popcoon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.ui.theme.AppIcons
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

/**
 * 商品画像コンポーネント。
 *
 * Apple HIG:
 *  - 画像はプレースホルダーを置いて、読み込み完了後にフェードイン
 *  - 読み込み失敗時は中立的な「画像なし」アイコンのフォールバック
 *    (以前はプラットフォーム別の絵文字だったが、実ブランドと乖離した誤解を招く
 *    表現だったため撤去。プラットフォーム名自体は隣接する PlatformChip が示す — 商用リリース監査で発見)
 *  - 正方形またはアスペクト比 1:1 を維持
 */
@Composable
fun ProductImage(
    imageUrl: String?,
    size: Dp = 60.dp,
    cornerRadius: Dp = CornerRadius.card,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNullOrEmpty()) {
            ImagePlaceholderIcon(size)
        } else {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading ->
                        // Apple HIG: 読み込み中はシマー
                        ShimmerBox(modifier = Modifier.fillMaxSize(), height = size)
                    is AsyncImagePainter.State.Error ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ImagePlaceholderIcon(size)
                        }
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }
    }
}

/** 装飾的なフォールバック — 隣接する PlatformChip 等が意味を伝えるため contentDescription は null。 */
@Composable
private fun ImagePlaceholderIcon(containerSize: Dp) {
    Icon(
        AppIcons.ImagePlaceholder,
        contentDescription = null,
        modifier = Modifier.size(containerSize / 2),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
