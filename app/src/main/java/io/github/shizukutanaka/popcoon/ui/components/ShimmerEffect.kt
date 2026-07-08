package io.github.shizukutanaka.popcoon.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.Spacing

/**
 * シマーエフェクト — Apple 流「コンテンツの構造を先に見せる」ローディング。
 *
 * Apple HIG より:
 *  - スピナーはユーザーに「待て」という命令
 *  - スケルトンはコンテンツが来ることを約束し、不安を取り除く
 *  - 実際のコンテンツと同じレイアウトで表示すると遷移が自然になる
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    height: Dp = 20.dp,
    shape: RoundedCornerShape = RoundedCornerShape(CornerRadius.tag),
) {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant,
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f),
    )

    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(brush),
    )
}

/** 検索結果1行分のスケルトン */
@Composable
fun ProductCardSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerRadius.card))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.ml),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ShimmerBox(modifier = Modifier.width(60.dp), height = 22.dp)
            ShimmerBox(modifier = Modifier.width(100.dp), height = 22.dp)
            ShimmerBox(modifier = Modifier.width(50.dp), height = 22.dp)
        }
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.85f), height = 14.dp)
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f), height = 14.dp)
    }
}

/** 商品詳細画面のスケルトン */
@Composable
fun ProductDetailSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.ml),
        verticalArrangement = Arrangement.spacedBy(Spacing.ml),
    ) {
        // タイトル
        ShimmerBox(modifier = Modifier.fillMaxWidth(), height = 24.dp)
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.7f), height = 24.dp)

        Spacer(Modifier.height(4.dp))

        // スコアカード
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(CornerRadius.card))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ShimmerBox(modifier = Modifier.fillMaxSize())
        }

        // 価格カード
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ShimmerBox(modifier = Modifier.width(80.dp), height = 16.dp)
                ShimmerBox(modifier = Modifier.width(100.dp), height = 16.dp)
            }
        }

        // チャート
        ShimmerBox(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            shape = RoundedCornerShape(CornerRadius.card),
        )
    }
}
