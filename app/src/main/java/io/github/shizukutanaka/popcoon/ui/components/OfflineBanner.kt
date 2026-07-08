package io.github.shizukutanaka.popcoon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import io.github.shizukutanaka.popcoon.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.ui.theme.Spacing
import io.github.shizukutanaka.popcoon.ui.theme.IconSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.shizukutanaka.popcoon.ui.util.ConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * オフライン時に画面上部に表示する非侵襲的バナー。
 *
 * Apple HIG:
 *  - オフライン状態を明確に伝える (ユーザーを混乱させない)
 *  - しかし操作の邪魔をしない (検索バーを隠さない)
 *  - ネット復旧時は自動消去
 */
@Composable
fun OfflineBanner(viewModel: OfflineBannerViewModel = hiltViewModel()) {
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = isOffline,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Surface(
            color = Color(0xFFB8860B),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    AppIcons.Offline,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(IconSize.sm),
                )
                Text(
                    stringResource(R.string.offline_cached),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@HiltViewModel
class OfflineBannerViewModel @Inject constructor(
    connectivity: ConnectivityObserver,
) : ViewModel() {
    val isOffline: StateFlow<Boolean> = connectivity.status
        .map { it != ConnectivityObserver.Status.AVAILABLE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
}
