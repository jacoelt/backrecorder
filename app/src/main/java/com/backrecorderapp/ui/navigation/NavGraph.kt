package com.backrecorderapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.backrecorderapp.ui.disclaimer.DisclaimerScreen
import com.backrecorderapp.ui.disclaimer.DisclaimerViewModel
import com.backrecorderapp.ui.main.MainScreen
import com.backrecorderapp.ui.settings.SettingsScreen

object Routes {
    const val DISCLAIMER = "disclaimer"
    const val MAIN = "main"
    const val SETTINGS = "settings"
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val disclaimerViewModel: DisclaimerViewModel = hiltViewModel()
    val disclaimerAccepted by disclaimerViewModel.disclaimerAccepted.collectAsState()

    val startDestination = if (disclaimerAccepted) Routes.MAIN else Routes.DISCLAIMER

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.DISCLAIMER) {
            DisclaimerScreen(
                onAccepted = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.DISCLAIMER) { inclusive = true }
                    }
                },
                onDeclined = {
                    // Close the app
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            )
        }

        composable(Routes.MAIN) {
            MainScreen(
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onShowDisclaimer = {
                    navController.navigate(Routes.DISCLAIMER)
                }
            )
        }
    }
}
