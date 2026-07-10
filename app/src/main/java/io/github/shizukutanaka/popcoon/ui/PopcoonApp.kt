package io.github.shizukutanaka.popcoon.ui

import androidx.activity.compose.ReportDrawnAfter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import io.github.shizukutanaka.popcoon.ui.theme.AppIcons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.res.stringResource
import io.github.shizukutanaka.popcoon.R
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.shizukutanaka.popcoon.IntentEvent
import io.github.shizukutanaka.popcoon.feature.settings.UserPreferences
import io.github.shizukutanaka.popcoon.ui.screens.onboarding.OnboardingScreen
import io.github.shizukutanaka.popcoon.ui.theme.PopcoonTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── タブ定義 ─────────────────────────────────────────────────────────────
internal enum class Tab(
    val route: String,
    @androidx.annotation.StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    SEARCH("search", R.string.nav_search, AppIcons.Search),
    WATCHLIST("watchlist", R.string.nav_watchlist, AppIcons.Save),
    SETTINGS("settings", R.string.nav_settings, AppIcons.Settings),
}

// ── Root Composable ───────────────────────────────────────────────────────
@Composable
fun PopcoonApp(
    initialEvent: StateFlow<IntentEvent> = MutableStateFlow(IntentEvent.None),
    viewModel: AppRootViewModel = hiltViewModel(),
) {
    PopcoonTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val event by initialEvent.collectAsStateWithLifecycle()

        Surface(modifier = Modifier.fillMaxSize()) {
            // アニメーション付き状態切り替え (Apple 原則: 状態遷移はスムーズに)
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                },
                label = "rootStateTransition",
            ) { s ->
                when (s) {
                    AppRootState.Loading -> {
                        // この間はネイティブ SplashScreen (MainActivity.installSplashScreen +
                        // setKeepOnScreenCondition) がまだ表示されたままなので、ここは
                        // 実際には描画されない (Compose 初回コンポジションより先にスプラッシュが
                        // 消えることはない)。空のままで問題ない。
                    }
                    AppRootState.Onboarding ->
                        OnboardingScreen(onComplete = viewModel::markOnboarded)
                    AppRootState.Ready ->
                        MainWithTabs(intentEvent = event)
                }
            }
        }
    }

    if (state == AppRootState.Ready) {
        ReportDrawnAfter { /* TTFD 計測 */ }
    }
}

// ── タブバー付きメイン画面 ────────────────────────────────────────────────
@Composable
private fun MainWithTabs(intentEvent: IntentEvent) {
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route

    // タブバーを表示するルートのセット
    val tabRoutes = Tab.entries.map { it.route }.toSet()
    val showTabBar = currentRoute in tabRoutes

    // 共有URLが (短縮URL解決後も) どのECサイトにも分類できなかった場合の通知。
    // 以前は MainActivity.handleIntent() が無言で return するだけで、ユーザーは
    // 「共有したのに何も起きない」状態のまま放置されていた (機能過不足監査で発見)。
    val snackbarHostState = remember { SnackbarHostState() }
    val shareUnrecognizedMessage = stringResource(R.string.share_url_unrecognized)

    // Intent 由来の遷移
    LaunchedEffect(intentEvent) {
        when (intentEvent) {
            is IntentEvent.OpenProduct ->
                navController.navigate("detail/${intentEvent.productKey}") {
                    launchSingleTop = true
                }
            is IntentEvent.StartSearch -> {
                navController.navigate(Tab.SEARCH.route) {
                    launchSingleTop = true
                }
            }
            IntentEvent.OpenBarcode ->
                navController.navigate("barcode") { launchSingleTop = true }
            IntentEvent.OpenWatchlist ->
                navController.navigateToTab(Tab.WATCHLIST)
            IntentEvent.ShareUnrecognized ->
                snackbarHostState.showSnackbar(shareUnrecognizedMessage)
            IntentEvent.None -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                io.github.shizukutanaka.popcoon.ui.components.OfflineBanner()
            }
        },
        bottomBar = {
            // Apple タブバー相当: 常時表示 (詳細画面では非表示)
            AnimatedVisibility(
                visible = showTabBar,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.navigateToTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        PopcoonNavGraph(
            navController = navController,
            modifier = Modifier.padding(padding),
        )
    }
}

// ── Root ViewModel ────────────────────────────────────────────────────────
sealed interface AppRootState {
    data object Loading : AppRootState
    data object Onboarding : AppRootState
    data object Ready : AppRootState
}

@HiltViewModel
class AppRootViewModel @Inject constructor(
    private val prefs: UserPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow<AppRootState>(AppRootState.Loading)
    val state: StateFlow<AppRootState> = _state

    init {
        viewModelScope.launch {
            _state.value = if (prefs.onboarded.first())
                AppRootState.Ready else AppRootState.Onboarding
        }
    }

    fun markOnboarded() {
        viewModelScope.launch {
            prefs.setOnboarded(true)
            _state.value = AppRootState.Ready
        }
    }
}
