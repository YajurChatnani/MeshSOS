package com.example.meshsos.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.meshsos.ui.viewmodel.MainViewModel

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Connection : Screen("connection")
    object History : Screen("history")
    object Settings : Screen("settings")
    object EmergencyAlert : Screen("emergency_alert/{messageId}") {
        fun createRoute(messageId: String? = null): String {
            return if (!messageId.isNullOrBlank()) "emergency_alert/$messageId" else "emergency_alert/latest"
        }
    }
}

@Composable
fun MainNavigation(
    viewModel: MainViewModel,
    initialEmergencyMessageId: String? = null,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    // Navigate immediately if launched with an emergency message ID
    LaunchedEffect(initialEmergencyMessageId) {
        if (!initialEmergencyMessageId.isNullOrBlank()) {
            navController.navigate(Screen.EmergencyAlert.createRoute(initialEmergencyMessageId)) {
                launchSingleTop = true
            }
        }
    }

    // Auto-navigate to Emergency Alert Screen when an incoming SOS event is received while app is open
    LaunchedEffect(Unit) {
        viewModel.incomingAlertEvents.collect { alert ->
            navController.navigate(Screen.EmergencyAlert.createRoute(alert.messageId)) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToConnection = { navController.navigate(Screen.Connection.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToEmergencyAlert = { messageId ->
                    navController.navigate(Screen.EmergencyAlert.createRoute(messageId))
                }
            )
        }

        composable(Screen.Connection.route) {
            ConnectionScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAlert = { messageId ->
                    navController.navigate(Screen.EmergencyAlert.createRoute(messageId))
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "emergency_alert/{messageId}",
            arguments = listOf(navArgument("messageId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            val messageId = backStackEntry.arguments?.getString("messageId")?.takeIf { it != "latest" }
            IncomingSosScreen(
                viewModel = viewModel,
                initialMessageId = messageId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
