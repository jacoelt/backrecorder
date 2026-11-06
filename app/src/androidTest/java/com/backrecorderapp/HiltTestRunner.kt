package com.backrecorderapp

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.backrecorderapp.service.BootReceiver
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps [BackRecorderApp] for [HiltTestApplication] in instrumented tests, because the real
 * application's `onCreate` connects billing and resumes cloud sync.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        setBootReceiverEnabled(false)
    }

    override fun onDestroy() {
        setBootReceiverEnabled(true)
        super.onDestroy()
    }

    /**
     * [HiltTestApplication]'s component is built by `HiltAndroidRule`, so it does not exist until a
     * test starts. A `BOOT_COMPLETED` broadcast reaching the `@AndroidEntryPoint` [BootReceiver]
     * outside that window therefore kills the whole app process with "The component was not
     * created", and every test in the run fails to start. The system delivers that broadcast when
     * the app is installed while a device is still finishing boot — exactly what an emulator-based
     * CI job does — so the receiver is switched off for the duration of the run and restored after.
     */
    private fun setBootReceiverEnabled(enabled: Boolean) {
        val context = targetContext
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )
    }
}
