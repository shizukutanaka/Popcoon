package com.example.popcoon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.popcoon.ui.theme.CornerRadius
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

/**
 * 商品画像コンポーネント。
 *
 * Apple HIG:
 *  - 画像はプレースホルダーを置いて、読み込み完了後にフェードイン
 *  - 読み込み失敗時はプラットフォームカラーのフォールバック
 *  - 正方形またはアスペクト比 1:1 を維持
 */
@Composable
fun ProductImage(
    imageUrl: String?,
    platformEmoji: String = "🛒",
    size: Dp = 60.dp,
    cornerRadius: Dp = CornerRadius.card,
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
            // フォールバック: プラットフォーム絵文字
            Text(platformEmoji, style = MaterialTheme.typography.titleLarge)
        } else {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading ->
                        // Apple HIG: 読み込み中はシマー
                        ShimmerBox(modifier = Modifier.fillMaxSize(), height = size)
                    is AsyncImagePainter.State.Error ->
                        // 失敗時はフォールバック絵文字
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(platformEmoji, style = MaterialTheme.typography.titleLarge)
                        }
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }
    }
}
