package com.rift.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rift.ui.history.HistoryScreen
import com.rift.ui.results.ResultsScreen
import com.rift.ui.scanning.QuickScanScreen
import com.rift.ui.scanning.ScanningScreen
import com.rift.ui.setup.BlueprintSetupScreen
import com.rift.ui.setup.HomeScreen
import com.rift.ui.setup.SourcePlacementScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object BlueprintSetup : Screen("blueprint_setup")
    object QuickScan : Screen("quick_scan")
    object Scanning : Screen("scanning/{sessionId}") {
        fun createRoute(sessionId: String) = "scanning/$sessionId"
    }
    object Results : Screen("results/{sessionId}") {
        fun createRoute(sessionId: String) = "results/$sessionId"
    }
    object SourcePlacement : Screen("source_placement/{sessionId}") {
        fun createRoute(sessionId: String) = "source_placement/$sessionId"
    }
    object History : Screen("history")
}

@Composable
fun RiftNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onStartNewScan = { navController.navigate(Screen.BlueprintSetup.route) },
                onQuickScan = { navController.navigate(Screen.QuickScan.route) },
                onViewHistory = { navController.navigate(Screen.History.route) }
            )
        }

        composable(Screen.QuickScan.route) {
            QuickScanScreen(
                onBack = { navController.popBackStack() },
                onStartFullScan = {
                    navController.navigate(Screen.BlueprintSetup.route) {
                        popUpTo(Screen.QuickScan.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.BlueprintSetup.route) {
            BlueprintSetupScreen(
                onSetupComplete = { sessionId ->
                    navController.navigate(Screen.Scanning.createRoute(sessionId)) {
                        popUpTo(Screen.BlueprintSetup.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Scanning.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStack ->
            val sessionId = backStack.arguments?.getString("sessionId") ?: return@composable
            ScanningScreen(
                sessionId = sessionId,
                onSessionComplete = { id ->
                    navController.navigate(Screen.Results.createRoute(id)) {
                        popUpTo(Screen.Scanning.route) { inclusive = true }
                    }
                },
                onBack = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Results.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStack ->
            val sessionId = backStack.arguments?.getString("sessionId") ?: return@composable
            ResultsScreen(
                sessionId = sessionId,
                onPlaceSources = {
                    navController.navigate(Screen.SourcePlacement.createRoute(sessionId))
                },
                onBack = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.SourcePlacement.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStack ->
            val sessionId = backStack.arguments?.getString("sessionId") ?: return@composable
            SourcePlacementScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                onSessionSelected = { sessionId ->
                    navController.navigate(Screen.Results.createRoute(sessionId))
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
