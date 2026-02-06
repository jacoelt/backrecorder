package com.backrecorderapp

import android.Manifest.permission
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.backrecorderapp.helpers.BillingManager
import com.backrecorderapp.helpers.GDriveHelper
import com.backrecorderapp.helpers.SecurePreferences
import com.backrecorderapp.helpers.SettingsDataStore
import com.backrecorderapp.helpers.SubscriptionManager
import com.backrecorderapp.helpers.SubscriptionMode
import com.backrecorderapp.helpers.SubscriptionOffer
import com.backrecorderapp.helpers.TrialManager
import com.backrecorderapp.services.RecordingService
import com.backrecorderapp.services.SaveRecordingService
import com.backrecorderapp.ui.MainScreen
import com.backrecorderapp.ui.MainScreenMode
import com.backrecorderapp.ui.PaywallReason
import com.backrecorderapp.ui.PremiumPaywallDialog
import com.backrecorderapp.ui.SubscriptionPicker
import com.backrecorderapp.ui.TermsOfUseScreen
import com.backrecorderapp.ui.TrialDialog
import com.backrecorderapp.ui.model.MainViewModel
import com.backrecorderapp.ui.theme.BackRecorderTheme
import com.google.crypto.tink.config.TinkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

const val SKU_ID = "main_subscription"
const val ALPHA_DISABLED = 0.5f
const val MAX_MINUTES_FREE_MODE = 15
const val MAX_MINUTES_TOTAL = 24 * 60  // 24h
const val TRIAL_LENGTH = 30  // days

class MainActivity : AppCompatActivity() {

    private var recordingService: RecordingService? = null
    private var isBound = false
    private val isRecording = mutableStateOf(false)
    private var currentRecordingDuration = mutableIntStateOf(0)
    private var trialDaysRemaining: Int = 30
    private lateinit var settings: SettingsDataStore
    private lateinit var gDriveHelper: GDriveHelper
    private lateinit var billingManager: BillingManager
    private var securePreferences = SecurePreferences(this)
    private lateinit var subscriptionOfferList: List<SubscriptionOffer>
    private var pendingSaveStart = mutableStateOf<Int?>(null)
    private var pendingSaveEnd = mutableStateOf<Int?>(null)
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var viewModel: MainViewModel

    companion object {
        private const val TAG = "MainActivity"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isBound = true
            updateRecordingState()
            recordingService!!.registerCallbackCurrentDuration { updateCurrentRecordingDuration(it) }
            recordingService!!.registerGDriveHelper(gDriveHelper)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    private val saveAudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!

            val intent = Intent(this, SaveRecordingService::class.java).apply {
                action = SaveRecordingService.ACTION_START
                putExtra(SaveRecordingService.EXTRA_OUTPUT_URI, uri)
                putExtra(SaveRecordingService.EXTRA_DURATION_START, pendingSaveStart.value)
                putExtra(SaveRecordingService.EXTRA_DURATION_END, pendingSaveEnd.value)
            }

            ContextCompat.startForegroundService(this, intent)

            pendingSaveStart.value = null
            pendingSaveEnd.value = null
        }
    }

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startRecording()
            } else {
                Toast.makeText(this, "Permission denied. Cannot record audio.", Toast.LENGTH_SHORT).show()
            }
        }

    private val authResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK || result.data != null) {
            gDriveHelper.handleAuthResponse(result.data) { success -> afterGoogleSignIn(success) }
        } else {
            Log.e("MainActivity", "Auth canceled or failed")
        }
    }

    override fun onStart() {
        super.onStart()

        Intent(this, RecordingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()

        lifecycleScope.launch {
            billingManager.syncSubscriptionState()
            subscriptionManager.load()
            try {
                subscriptionOfferList = billingManager.getSubscriptionOffers(SKU_ID)
            } catch (e: Exception) {
                Log.w(TAG, "Error while getting subscriptions offers", e)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ===== Global exception handler =====
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("GlobalError", "Uncaught exception in thread ${thread.name}", throwable)

            lifecycleScope.launch {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        "Unexpected error: ${throwable.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        try {
            TinkConfig.register()
            settings = SettingsDataStore.getInstance(this.applicationContext)
            subscriptionManager = SubscriptionManager(settings)
            gDriveHelper = GDriveHelper(this.applicationContext, securePreferences)
            billingManager = BillingManager(this.applicationContext, subscriptionManager)
            viewModel = MainViewModel(subscriptionManager)
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            Toast.makeText(this, "Initialization error: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        if (ContextCompat.checkSelfPermission(applicationContext, permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(permission.READ_EXTERNAL_STORAGE, permission.WRITE_EXTERNAL_STORAGE),
                2222
            )
        }

        trialDaysRemaining = TrialManager.trialDaysRemaining(this.applicationContext).toInt()

        // Start billing connection, then evaluate subscription state
        try {
            billingManager.startConnection(onConnected = {
                lifecycleScope.launch {
                    billingManager.syncSubscriptionState()
                    subscriptionManager.load()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error when connecting to google billing", e)
            Toast.makeText(
                this.applicationContext,
                "Error when connecting to google billing, cannot check current subscription.",
                Toast.LENGTH_SHORT
            ).show()
        }

        setContent {
            BackRecorderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    var hasAcceptedTerms by rememberSaveable { mutableStateOf<Boolean?>(null) }
                    var showTerms by rememberSaveable { mutableStateOf(false) }
                    var mustAcceptTerms by rememberSaveable { mutableStateOf(false) }
                    var isFirstLaunch by rememberSaveable { mutableStateOf(false) }
                    var canUseCloud by rememberSaveable { mutableStateOf(false) }
                    var premiumDialogReason by rememberSaveable { mutableStateOf<PaywallReason?>(null) }
                    var showSubscriptionPicker by rememberSaveable { mutableStateOf(false) }
                    var selectedOffer by remember { mutableStateOf<SubscriptionOffer?>(null) }
                    var screenMode by remember { mutableStateOf(MainScreenMode.RECORDING) }

                    // Load state
                    LaunchedEffect(Unit) {
                        hasAcceptedTerms = settings.isTermsAccepted()
                        if (hasAcceptedTerms == false) {
                            showTerms = true
                            mustAcceptTerms = true
                        }

                        isFirstLaunch = settings.isFirstLaunch()
                        canUseCloud = canUseCloud()
                    }


                    // Display either Terms screen or main content
                    if (showTerms && mustAcceptTerms) {
                        TermsOfUseScreen(
                            onAccepted = {
                                hasAcceptedTerms = true
                                showTerms = false
                                mustAcceptTerms = false
                            },
                            onDeclined = { finish() },
                            readOnly = false
                        )

                    } else if (showTerms && !mustAcceptTerms) {
                        TermsOfUseScreen(
                            onClosed = { showTerms = false },
                            readOnly = true
                        )

                    } else {

                        // Display dialogs
                        if (isFirstLaunch) {
                            TrialDialog(
                                onOpenSubscriptions = {
                                    Log.d(TAG, "TrialDialog: onOpenSubscriptions")
                                    showSubscriptionPicker = true
                                    isFirstLaunch = false
                                },
                                onDismiss = {
                                    lifecycleScope.launch { settings.markFirstLaunchDone() }
                                    isFirstLaunch = false
                                }
                            )
                        }

                        if (premiumDialogReason != null) {
                            PremiumPaywallDialog(
                                reason = premiumDialogReason!!,
                                yearlyPrice = stringResource(R.string.yearly_price),
                                monthlyPrice = stringResource(R.string.monthly_equivalent_price),
                                trialEndDate = getTrialEndDate(),
                                onStartTrial = {
                                    Log.d(TAG, "PremiumRequiredDialog: onOpenSubscriptions")
                                    premiumDialogReason = null
                                    showSubscriptionPicker = true
                                },
                                onDismiss = { premiumDialogReason = null }
                            )
                        }

                        if (showSubscriptionPicker){
                            SubscriptionPicker(
                                offers = subscriptionOfferList,
                                selectedOffer = selectedOffer,
                                onOfferSelected = { selectedOffer = it },
                                onDismiss = { showSubscriptionPicker = false },
                                onSubscribeClicked = { offer: SubscriptionOffer ->
                                    billingManager.launchSubscriptionPurchase(this@MainActivity, offer)
                                    showSubscriptionPicker = false
                                }
                            )
                        }

                        MainScreen(
                            startRecording = { startRecording() },
                            stopRecording = { stopRecording() },
                            isRecording = isRecording,
                            currentRecordingDuration,
                            startSaveRecording = { start, end ->
                                pendingSaveStart.value = start
                                pendingSaveEnd.value = end
                                launchFilePicker()
                                return@MainScreen true
                            },
                            useGDrive = canUseCloud,
                            onToggleGDrive = { enabled ->
                                lifecycleScope.launch {
                                    settings.setUseGDrive(enabled)
                                    if (enabled) setupGDrive()
                                }
                            },
                            onShowTerms = { showTerms = true },
                            onShowPremiumDialog = { reason: PaywallReason -> premiumDialogReason = reason },
                            trialEndDate = getTrialEndDate(),
                            viewModel,
                            screenMode,
                            onModeChange = { mode ->
                                screenMode = mode
                            },
                        )
                    }
                }
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                val intent = Intent(this, RecordingService::class.java)
                ContextCompat.startForegroundService(this, intent)
                this.isRecording.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                Toast.makeText(this, "Unable to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            requestAudioPermissionLauncher.launch(permission.RECORD_AUDIO)
        }
    }

    private fun stopRecording() {
        Log.d("MainActivity", "stopRecording")
        try {
            recordingService?.stopRecording()
            isRecording.value = false
            stopService(Intent(this, RecordingService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error while stopping recording", e)
            Toast.makeText(this, "Error while stopping recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchFilePicker() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val audioFileName = "record_$timeStamp.m4a"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "audio/m4a"
            putExtra(Intent.EXTRA_TITLE, audioFileName)
        }

        saveAudioLauncher.launch(intent)
    }

    private fun updateRecordingState() {
        isRecording.value = recordingService?.isRecording() ?: false
    }

    private fun updateCurrentRecordingDuration(duration: Int) {
        Log.d(TAG, "Update recording duration: $duration")
        currentRecordingDuration.intValue = duration
    }

    private fun setupGDrive() {
        gDriveHelper.launchSignIn(authResultLauncher)
    }

    private fun afterGoogleSignIn(success: Boolean) {
        fun onFail() {
            lifecycleScope.launch {
                settings.setUseGDrive(false)
            }
            Toast.makeText(
                this.applicationContext,
                R.string.error_setup_gdrive,
                Toast.LENGTH_SHORT
            ).show()
        }

        if (success) {
            try {
                gDriveHelper.setupDrive()
            } catch (e: IllegalStateException) {
                onFail()
            }
        } else {
            onFail()
        }
    }

    private suspend fun canUseCloud(): Boolean {
        val useGDrivePref = settings.getUseGDrive()
        return useGDrivePref && (subscriptionManager.isTrial() || subscriptionManager.isPremium())
    }

    private fun maxRecordingDurationMinutes(): Int =
        when (subscriptionManager.mode.value) {
            SubscriptionMode.FREE -> MAX_MINUTES_FREE_MODE
            SubscriptionMode.TRIAL,
            SubscriptionMode.PREMIUM -> MAX_MINUTES_TOTAL
        }

    private fun getTrialEndDate(): String {
        //TODO: Fix to not always use current date
        val date = LocalDate.now().plusDays(TRIAL_LENGTH.toLong())

        val formatter = DateTimeFormatter
            .ofPattern("d MMM", Locale.getDefault())

        return date.format(formatter)
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.endConnection()
    }
}
