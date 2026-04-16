package com.backrecorderapp.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    const val DISCLAIMER_REVIEW = "disclaimer_review"
    const val MAIN = "main"
    const val SETTINGS = "settings"
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val disclaimerViewModel: DisclaimerViewModel = hiltViewModel()
    val disclaimerAccepted by disclaimerViewModel.disclaimerAccepted.collectAsState()

    // Wait for DataStore to load before choosing start destination
    val accepted = disclaimerAccepted ?: run {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        return
    }

    val startDestination = if (accepted) Routes.MAIN else Routes.DISCLAIMER

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.DISCLAIMER) {
            DisclaimerScreen(
                onAccepted = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.DISCLAIMER) { inclusive = true }
                    }
                },
                onDeclined = {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            )
        }

        composable(Routes.DISCLAIMER_REVIEW) {
            DisclaimerScreen(
                onAccepted = {},
                onDeclined = { navController.popBackStack() },
                readOnly = true
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
                    navController.navigate(Routes.DISCLAIMER_REVIEW)
                }
            )
        }
    }
}
