package com.backrecorderapp

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps [BackRecorderApp] for [HiltTestApplication] in instrumented tests.
 *
 * `@HiltAndroidTest` needs an application that hosts the generated test component, and the real
 * one cannot serve: its `onCreate` connects billing and resumes cloud sync, which an instrumented
 * test has no business doing. Wired up as `testInstrumentationRunner` in `app/build.gradle.kts`.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
