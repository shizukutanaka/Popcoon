package com.example.popcoon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.popcoon.ui.screens.barcode.BarcodeScreen
import com.example.popcoon.ui.screens.calendar.SaleCalendarScreen
import com.example.popcoon.ui.screens.detail.ProductDetailScreen
import com.example.popcoon.ui.screens.detail.navigateToDetail
import com.example.popcoon.ui.screens.search.SearchScreen
import com.example.popcoon.ui.screens.settings.SettingsScreen
import com.example.popcoon.ui.screens.watchlist.WatchlistScreen

/**
 * NavHost 定義を PopcoonApp から独立させる。
 *
 * Apple Navigator 相当の責務分離:
 *  - PopcoonApp: ルート状態 (Loading/Onboarding/Ready) の管理
 *  - PopcoonNavGraph: 画面間遷移ロジックの集約
 *  - 各 Screen: UI のみに専念
 *
 * Robert C. Martin:
 *  - Single Responsibility — NavGraph は経路定義だけを担う
 */
@Composable
fun PopcoonNavGraph(
    navController: NavHostController,
    onGoSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Tab.SEARCH.route,
        modifier = modifier,
    ) {
        composable(Tab.SEARCH.route) {
            SearchScreen(
                onProductClick = { product -> navController.navigateToDetail(product) },
                onSettings = { navController.navigate(Tab.SETTINGS.route) },
                onWatchlist = { navController.navigate(Tab.WATCHLIST.route) },
                onBarcode = { navController.navigate("barcode") },
                onSaleCalendar = { navController.navigate("sale_calendar") },
            )
        }

        composable("sale_calendar") {
            SaleCalendarScreen(onBack = { navController.popBackStack() })
        }

        composable(Tab.WATCHLIST.route) {
            WatchlistScreen(
                onItemClick = { key -> navController.navigate("detail/$key") },
                onBack = { navController.popBackStack() },
                onGoSearch = {
                    navController.navigate(Tab.SEARCH.route) { launchSingleTop = true }
                },
            )
        }

        composable(Tab.SETTINGS.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable("detail/{productKey}") { entry ->
            val key = entry.arguments?.getString("productKey")
            if (key == null) {
                navController.popBackStack()
                return@composable
            }
            ProductDetailScreen(
                productKey = key,
                onBack = { navController.popBackStack() },
            )
        }

        composable("barcode") {
            BarcodeScreen(
                onQueryResult = { query ->
                    navController.navigate(Tab.SEARCH.route) {
                        popUpTo(Tab.SEARCH.route) { inclusive = true }
                    }
                    navController.getBackStackEntry(Tab.SEARCH.route)
                        .savedStateHandle["barcode_query"] = query
                },
                onProductResult = { key ->
                    navController.navigate("detail/$key") {
                        popUpTo(Tab.SEARCH.route)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** NavController 拡張: 画面を残さず確実にタブ切替 */
fun NavController.navigateToTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
