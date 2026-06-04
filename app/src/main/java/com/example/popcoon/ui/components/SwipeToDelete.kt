package com.example.popcoon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.popcoon.ui.theme.CornerRadius
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * スワイプで操作を露出するコンポーネント。
 *
 * Apple HIG より:
 *  - リストアイテムは左スワイプで削除アクションを出す (iOS 標準)
 *  - Android でも期待値が高まっている
 *  - 削除は赤、その他は青/緑で区別
 *
 * 使用箇所: WatchlistScreen の各アイテム
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val threshold = -200f
    val animatedOffset by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = tween(durationMillis = 150),
        label = "swipeOffset",
    )
    var visible by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = visible,
        exit = shrinkVertically() + fadeOut(),
    ) {
        Box {
            // 背景: 削除ボタン (赤)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(CornerRadius.card))
                    .background(Color(0xFFC0392B)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    "削除",
                    modifier = Modifier.padding(end = 20.dp),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }

            // フォアグラウンド: 実コンテンツ
            Box(
                modifier = Modifier
                    .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            // 左方向のみ (右スワイプは無視)
                            offsetX = (offsetX + delta).coerceIn(threshold, 0f)
                        },
                        onDragStopped = {
                            if (offsetX < threshold / 2) {
                                // 閾値超えで削除実行
                                visible = false
                                onDelete()
                            } else {
                                // 戻す
                                offsetX = 0f
                            }
                        },
                    ),
            ) {
                content()
            }
        }
    }
}
