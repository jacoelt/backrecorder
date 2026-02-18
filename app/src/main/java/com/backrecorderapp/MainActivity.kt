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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.backrecorderapp.helpers.BillingManager
import com.backrecorderapp.helpers.GDriveHelper
import com.backrecorderapp.helpers.SettingsDataStore
import com.backrecorderapp.helpers.SubscriptionManager
import com.backrecorderapp.helpers.SubscriptionOffer
import com.backrecorderapp.helpers.SubscriptionOffers
import com.backrecorderapp.helpers.TrialManager
import com.backrecorderapp.services.RecordingService
import com.backrecorderapp.services.SaveRecordingService
import com.backrecorderapp.ui.MainScreen
import com.backrecorderapp.ui.MainScreenMode
import com.backrecorderapp.ui.PaywallReason
import com.backrecorderapp.ui.PremiumPaywallDialog
import com.backrecorderapp.ui.SubscriptionPicker
import com.backrecorderapp.ui.TermsOfUseScreen
import com.backrecorderapp.ui.model.MainViewModel
import com.backrecorderapp.ui.theme.BackRecorderTheme
import com.google.crypto.tink.config.TinkConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

const val ALPHA_DISABLED = 0.5f
const val MAX_MINUTES_FREE_MODE = 15

class MainActivity : AppCompatActivity() {

    private var recordingService: RecordingService? = null
    private var isBound = false
    private val recordingStatus = mutableStateOf(RecordingService.RecordingStatus.STOPPED)
    private val uiRecordingStatus = mutableStateOf(RecordingService.RecordingStatus.STOPPED)
    private var currentRecordingDuration = mutableIntStateOf(0)
    private var trialDaysRemaining: Int = TrialManager.TRIAL_PERIOD
    private lateinit var trialManager: TrialManager
    private lateinit var settings: SettingsDataStore
    private lateinit var gDriveHelper: GDriveHelper
    private lateinit var billingManager: BillingManager
    private var subscriptionOffers: SubscriptionOffers? = null
    private var pendingSaveStart = mutableStateOf<Int?>(null)
    private var pendingSaveEnd = mutableStateOf<Int?>(null)
    private var usingCloud = mutableStateOf(false)
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

            recordingService?.registerRecordingStateCallback { recordingState: RecordingService.RecordingStatus ->
                recordingStatus.value = recordingState
            }

            recordingService?.registerCallbackCurrentDuration {
                updateCurrentRecordingDuration(it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false

            // Try to re-bind
            bindRecordingService()
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
            switchUseGdrive(false)
            Log.e("MainActivity", "Auth canceled or failed")
        }
    }

    override fun onStart() {
        super.onStart()

        bindRecordingService()
    }

    private fun bindRecordingService() {
        Intent(this, RecordingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()

        lifecycleScope.launch {
            billingManager.syncSubscriptionState()
            subscriptionManager.load()
            switchUseGdrive(isUsingCloud())
            try {
                subscriptionOffers = billingManager.getSubscriptionOffers()
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

        try {
            TinkConfig.register()
            settings = SettingsDataStore.getInstance(this.applicationContext)
            trialManager = TrialManager(settings)
            subscriptionManager = SubscriptionManager(settings)
            gDriveHelper = GDriveHelper(this.applicationContext)
            billingManager = BillingManager(this.applicationContext, subscriptionManager, trialManager)
            viewModel = MainViewModel(subscriptionManager)
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            Toast.makeText(this, "Initialization error: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            trialManager.load()
            trialDaysRemaining = trialManager.trialDaysRemaining()
        }

        // Start billing connection, then evaluate subscription state
        try {
            billingManager.startConnection(onConnected = {
                lifecycleScope.launch {
                    billingManager.syncSubscriptionState()
                    subscriptionManager.load()

                    switchUseGdrive(isUsingCloud())
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
                    var premiumDialogReason by rememberSaveable { mutableStateOf<PaywallReason?>(null) }
                    var showSubscriptionPicker by rememberSaveable { mutableStateOf(false) }
                    var screenMode by rememberSaveable { mutableStateOf(MainScreenMode.RECORDING) }
                    var selectedOffer by remember { mutableStateOf<SubscriptionOffer?>(null) }

                    // Load state
                    LaunchedEffect(Unit) {
                        hasAcceptedTerms = settings.isTermsAccepted()
                        if (hasAcceptedTerms == false) {
                            showTerms = true
                            mustAcceptTerms = true
                        }

                        switchUseGdrive(isUsingCloud())
                    }

                    LaunchedEffect(recordingStatus.value) {
                        if (recordingStatus.value != RecordingService.RecordingStatus.STOPPED) {
                            uiRecordingStatus.value = recordingStatus.value
                        } else {
                            delay(300)
                            if (recordingStatus.value == RecordingService.RecordingStatus.STOPPED) {
                                uiRecordingStatus.value = RecordingService.RecordingStatus.STOPPED
                            }
                        }
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
                        if (premiumDialogReason != null) {
                            if (subscriptionOffers != null) {
                                PremiumPaywallDialog(
                                    reason = premiumDialogReason!!,
                                    yearlyPrice = subscriptionOffers!!.yearly.formattedPrice,
                                    monthlyPrice = stringResource(R.string.monthly_equivalent_of_yearly, subscriptionOffers!!.yearly.monthlyEquivalent),
                                    trialEndDate = getTrialEndDate(),
                                    onStartTrial = {
                                        Log.d(TAG, "PremiumRequiredDialog: onOpenSubscriptions")
                                        premiumDialogReason = null
                                        showSubscriptionPicker = true
                                    },
                                    onDismiss = { premiumDialogReason = null }
                                )
                            } else {
                                Toast.makeText(this, stringResource(R.string.error_no_offers), Toast.LENGTH_SHORT).show()
                                //TODO: Display an error window
                            }
                        }

                        if (showSubscriptionPicker) {
                            if (subscriptionOffers != null) {
                                SubscriptionPicker(
                                    offers = subscriptionOffers!!,
                                    selectedOffer = selectedOffer,
                                    onOfferSelected = { selectedOffer = it },
                                    onDismiss = { showSubscriptionPicker = false },
                                    onSubscribeClicked = { offer: SubscriptionOffer ->
                                        billingManager.launchSubscriptionPurchase(
                                            this@MainActivity,
                                            offer
                                        )
                                        showSubscriptionPicker = false
                                    }
                                )
                            } else {
                                Toast.makeText(this, stringResource(R.string.error_no_offers), Toast.LENGTH_SHORT).show()
                                //TODO: Display an error window
                            }
                        }

                        MainScreen(
                            startRecording = { startRecording() },
                            stopRecording = { stopRecording() },
                            recordingStatus = uiRecordingStatus,
                            currentRecordingDuration,
                            startSaveRecording = { start, end ->
                                if (start < (currentRecordingDuration.intValue - MAX_MINUTES_FREE_MODE)) {
                                    lifecycleScope.launch {
                                        if (ensureSubscriptionOffersLoaded()) {
                                            premiumDialogReason = PaywallReason.RECORDING_DURATION
                                        }
                                    }
                                    return@MainScreen false
                                }

                                pendingSaveStart.value = start
                                pendingSaveEnd.value = end
                                launchFilePicker()
                                true
                            },
                            useGDrive = usingCloud.value,
                            onToggleGDrive = { enabled ->
                                if (!subscriptionManager.isPremium() && !subscriptionManager.isTrial()) {
                                    lifecycleScope.launch {
                                        if (ensureSubscriptionOffersLoaded()) {
                                            premiumDialogReason = PaywallReason.CLOUD_BACKUP
                                        }
                                    }
                                    return@MainScreen
                                }
                                lifecycleScope.launch {
                                    if (enabled) {
                                        initiateSetupGDrive()
                                    } else {
                                        switchUseGdrive(false)
                                    }
                                }
                            },
                            onShowTerms = { showTerms = true },
                            onBannerClick = {
                                lifecycleScope.launch {
                                    if (ensureSubscriptionOffersLoaded()) {
                                        premiumDialogReason = PaywallReason.GENERIC
                                    }
                                }
                            },
                            trialEndDate = getTrialEndDate(),
                            viewModel,
                            screenMode,
                            onModeChange = { screenMode = it },
                        )
                    }
                }
            }
        }
    }

    private fun switchUseGdrive(newStatus: Boolean) {
        lifecycleScope.launch {
            settings.setUseGDrive(newStatus)
            usingCloud.value = newStatus
        }
    }

    private fun startRecording() {
        Log.d(TAG, "startRecording")
        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermissionLauncher.launch(permission.RECORD_AUDIO)
            return
        }

        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
        }

        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            Toast.makeText(
                this,
                "Unable to start recording: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stopRecording() {
        Log.d(TAG, "stopRecording")
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }

        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error while stopping recording", e)
            Toast.makeText(
                this,
                "Error while stopping recording: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
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

    private fun updateCurrentRecordingDuration(duration: Int) {
        Log.d(TAG, "Update recording duration: $duration")
        currentRecordingDuration.intValue = duration
    }

    private fun initiateSetupGDrive() {
        gDriveHelper.launchSignIn(authResultLauncher)
    }

    private fun afterGoogleSignIn(success: Boolean) {
        fun onFail() {
            switchUseGdrive(false)
            Toast.makeText(
                this.applicationContext,
                R.string.error_setup_gdrive,
                Toast.LENGTH_SHORT
            ).show()
        }

        if (success) {
            switchUseGdrive(true)
            try {
                gDriveHelper.setupDrive()
            } catch (e: IllegalStateException) {
                onFail()
            }
        } else {
            onFail()
        }
    }

    private suspend fun isUsingCloud(): Boolean {
        val useGDrivePref = settings.getUseGDrive()
        return useGDrivePref && (subscriptionManager.isTrial() || subscriptionManager.isPremium())
    }

    private fun getTrialEndDate(): String {
        val date = LocalDate.now().plusDays(TrialManager.TRIAL_PERIOD.toLong())  // TODO: Check how to get actual remaining days if user has already started a trial

        val formatter = DateTimeFormatter
            .ofPattern("d MMM", Locale.getDefault())

        return date.format(formatter)
    }

    private suspend fun ensureSubscriptionOffersLoaded(): Boolean {
        if (subscriptionOffers != null) return true

        return try {
            subscriptionOffers = billingManager.getSubscriptionOffers()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load subscription offers", e)
            false
        }
    }

    override fun onDestroy() {
        billingManager.endConnection()
        gDriveHelper.clear()
        super.onDestroy()
    }
}
