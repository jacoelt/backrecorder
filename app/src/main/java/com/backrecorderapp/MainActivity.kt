package com.backrecorderapp

import android.Manifest.permission
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.backrecorderapp.helpers.BillingManager
import com.backrecorderapp.helpers.GDriveHelper
import com.backrecorderapp.helpers.PREF_PREMIUM_ACTIVE
import com.backrecorderapp.helpers.SecurePreferences
import com.backrecorderapp.helpers.SettingsDataStore
import com.backrecorderapp.helpers.TrialManager
import com.backrecorderapp.services.RecordingService
import com.backrecorderapp.services.SaveRecordingService
import com.backrecorderapp.ui.PremiumRequiredDialog
import com.backrecorderapp.ui.RecordingScreen
import com.backrecorderapp.ui.SubscriptionOffer
import com.backrecorderapp.ui.SubscriptionPicker
import com.backrecorderapp.ui.TermsOfUseScreen
import com.backrecorderapp.ui.TrialDialog
import com.backrecorderapp.ui.theme.BackRecorderTheme
import com.google.crypto.tink.config.TinkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

const val SKU_ID = "main_subscription"
const val ALPHA_DISABLED = 0.5f

class MainActivity : AppCompatActivity() {

    private var recordingService: RecordingService? = null
    private var isBound = false
    private val isRecording = mutableStateOf(false)
    private var duration = mutableStateOf<Int?>(null)
    private var currentRecordingDuration = mutableIntStateOf(0)
    private var totalWeight = mutableStateOf("")
    private var trialDaysRemaining: Int = 30
    private var isSubscriptionActive = mutableStateOf(false)
    private var isFullVersionActive: Boolean = false
    private lateinit var settings: SettingsDataStore
    private lateinit var gDriveHelper: GDriveHelper
    private lateinit var billingManager: BillingManager
    private var securePreferences = SecurePreferences(this)
    private lateinit var subscriptionOfferList: List<SubscriptionOffer>

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
            }

            ContextCompat.startForegroundService(this, intent)
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
            isSubscriptionActive.value = isPremium()
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
            gDriveHelper = GDriveHelper(this.applicationContext, securePreferences)
            billingManager = BillingManager(this.applicationContext, securePreferences)
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
                    isSubscriptionActive.value = isPremium()
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

        isSubscriptionActive.value = isPremium()
        isFullVersionActive = trialDaysRemaining > 0 || isSubscriptionActive.value

        setContent {
            BackRecorderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val useGDrivePref by settings.getUseGDriveFlow().collectAsState(initial = false)
                    val useGDrive = isFullVersionActive && useGDrivePref

                    var hasAcceptedTerms by rememberSaveable { mutableStateOf<Boolean?>(null) }
                    var showTerms by rememberSaveable { mutableStateOf(false) }
                    var mustAcceptTerms by rememberSaveable { mutableStateOf(false) }
                    var isFirstLaunch by rememberSaveable { mutableStateOf(false) }
                    var showPremiumDialog by rememberSaveable { mutableStateOf(false) }
                    var showSubscriptionPicker by rememberSaveable { mutableStateOf(false) }
                    var selectedOffer by remember { mutableStateOf<SubscriptionOffer?>(null) }

                    // Load state
                    LaunchedEffect(Unit) {
                        hasAcceptedTerms = settings.isTermsAccepted()
                        if (hasAcceptedTerms == false) {
                            showTerms = true
                            mustAcceptTerms = true
                        }

                        val storedDuration = if (isFullVersionActive) {
                            settings.getRecordingDuration(RecordingService.DEFAULT_DURATION)
                        } else {
                            settings.setRecordingDuration(RecordingService.DEFAULT_DURATION)
                            RecordingService.DEFAULT_DURATION
                        }
                        duration.value = storedDuration
                        totalWeight.value = generateTotalWeightString(storedDuration)

                        isFirstLaunch = settings.isFirstLaunch()
                    }

                    if (duration.value == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {

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

                            if (showPremiumDialog) {
                                PremiumRequiredDialog (
                                    onOpenSubscriptions = {
                                        Log.d(TAG, "PremiumRequiredDialog: onOpenSubscriptions")
                                        showPremiumDialog = false
                                        showSubscriptionPicker = true
                                    },
                                    onDismiss = { showPremiumDialog = false }
                                )
                            }

                            if (showSubscriptionPicker){
                                SubscriptionPicker(
                                    offers = subscriptionOfferList,
                                    selectedOffer = selectedOffer,
                                    onOfferSelected = { selectedOffer = it },
                                    onDismiss = { showSubscriptionPicker = false },
                                    onSubscribeClicked = { offer ->
                                        billingManager.launchSubscriptionPurchase(this@MainActivity, offer)
                                        showSubscriptionPicker = false
                                    }
                                )
                            }

                            RecordingScreen(
                                startRecording = { startRecording() },
                                stopRecording = { stopRecording() },
                                isRecording = isRecording,
                                currentRecordingDuration,
                                duration,
                                totalWeight,
                                startSaveRecording = {
                                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    val audioFileName = "record_$timeStamp.m4a"
                                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        type = "audio/m4a"
                                        putExtra(Intent.EXTRA_TITLE, audioFileName)
                                    }
                                    saveAudioLauncher.launch(intent)
                                },
                                onDurationChange = {
                                    this.duration.value = it
                                    this.totalWeight.value =
                                        this.generateTotalWeightString(this.duration.value ?: RecordingService.DEFAULT_DURATION)
                                    lifecycleScope.launch {
                                        settings.setRecordingDuration(this@MainActivity.duration.value ?: RecordingService.DEFAULT_DURATION)
                                    }
                                },
                                useGDrive = useGDrive,
                                onToggleGDrive = { enabled ->
                                    lifecycleScope.launch {
                                        settings.setUseGDrive(enabled)
                                        if (enabled) setupGDrive()
                                    }
                                },
                                onShowTerms = { showTerms = true },
                                onShowPremiumDialog = { showPremiumDialog = true },
                                trialDaysRemaining = trialDaysRemaining,
                                isSubscriptionActive = isSubscriptionActive.value,
                                isFullVersionActive = isFullVersionActive,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isPremium(): Boolean {
        return securePreferences.getString(PREF_PREMIUM_ACTIVE)?.toBoolean() == true
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

    private fun updateRecordingState() {
        isRecording.value = recordingService?.isRecording() ?: false
    }

    private fun generateTotalWeightString(duration: Int): String {
        if (duration <= 0) return "0 B"
        var weight = RecordingService.BIT_RATE.toDouble() / 8 * duration * 60 / 1024
        var unit = "KB"

        if (weight > 1024 * 1024) {
            weight /= 1024 * 1024
            unit = "GB"
        } else if (weight > 1024) {
            weight /= 1024
            unit = "MB"
        }

        return DecimalFormat("#.##").format(weight) + " " + unit
    }

    private fun updateCurrentRecordingDuration(duration: Int) {
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

    override fun onDestroy() {
        super.onDestroy()
        billingManager.endConnection()
    }
}

