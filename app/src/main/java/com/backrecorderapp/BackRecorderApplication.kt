package com.backrecorderapp

import android.app.Application
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustConfig
import com.facebook.appevents.AppEventsLogger

class BackRecorderApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Facebook SDK — activates session tracking (install + app launch)
        AppEventsLogger.activateApp(this)

        // Adjust SDK — tracks installs and sessions
        val adjustConfig = AdjustConfig(
            this,
            "8vyd3ojphfk0",
            AdjustConfig.ENVIRONMENT_PRODUCTION
        )
        Adjust.initSdk(adjustConfig)
    }
}