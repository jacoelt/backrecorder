package com.backrecorderapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.backrecorderapp.data.billing.BillingRepository
import com.backrecorderapp.ui.navigation.NavGraph
import com.backrecorderapp.ui.theme.BackRecorderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var billingRepository: BillingRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BackRecorderTheme {
                NavGraph()
            }
        }
    }

    // A subscription changed in the Play Store is invisible to a process that already resolved
    // entitlement, so coming back to the app is when to ask again (#88).
    override fun onStart() {
        super.onStart()
        billingRepository.refreshEntitlement()
    }
}
