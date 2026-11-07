import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Version lives in version.properties so a release is a reviewable one-line change,
// and so the name and the generated code can never drift apart.
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    require(file.exists()) { "version.properties not found at ${file.path}" }
    file.inputStream().use { load(it) }
}

val appVersionName: String = requireNotNull(versionProps.getProperty("VERSION_NAME")?.trim()) {
    "VERSION_NAME is missing from version.properties"
}

// MAJOR.MINOR.PATCH with optional -prerelease and +build, per semver.org.
val semanticVersion = Regex(
    """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
        """(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""" +
        """(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"""
)
require(semanticVersion.matches(appVersionName)) {
    "VERSION_NAME '$appVersionName' is not semantic versioning. " +
        "Expected MAJOR.MINOR.PATCH, optionally -prerelease and +build (e.g. 1.4.0, 2.0.0-rc.1)."
}

val versionCodeBase: Int = requireNotNull(
    versionProps.getProperty("VERSION_CODE_BASE")?.trim()?.toIntOrNull()
) { "VERSION_CODE_BASE is missing from version.properties, or is not a whole number" }

// GitHub sets GITHUB_RUN_NUMBER and increments it per run of a given workflow, so every
// CI build gets a higher code than the last without anyone editing a file. Local builds
// see no such variable and stay pinned to the base.
val ciRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.trim()?.toIntOrNull() ?: 0
val appVersionCode: Int = versionCodeBase + ciRunNumber

android {
    namespace = "com.backrecorderapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.backrecorderapp"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // Substitutes HiltTestApplication; see HiltTestRunner.
        testInstrumentationRunner = "com.backrecorderapp.HiltTestRunner"

        // Runs `pm clear` on the app and test packages around every instrumented test, which
        // resets DataStore, the Room database, the cache directory *and* the runtime permission
        // grants. Only meaningful together with the orchestrator in `testOptions` below.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("RELEASE_KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("RELEASE_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Each instrumented test gets its own instrumentation process and, with `clearPackageData`,
    // its own clean app state. That isolation is a hard requirement, not a nicety: runtime
    // permission grants are package-wide and permanent for the life of an instrumentation run, so
    // `GrantPermissionRule` in one class (RecordingSegmentTest grants RECORD_AUDIO) leaks into
    // every later class, and a test of the *denied* branch would silently stop seeing a dialog and
    // pass vacuously. Revoking in-process is not an alternative — the platform kills the app's
    // processes on revoke, and under instrumentation that is the test run itself.
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    // Debug-only manifest activity that createComposeRule()/createEmptyComposeRule() host in.
    debugImplementation(libs.compose.ui.test.manifest)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Google Play Billing
    implementation(libs.billing.ktx)

    // Google Drive
    implementation(libs.google.api.client.android)
    implementation(libs.google.drive)
    implementation(libs.play.services.auth)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.core.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    androidTestImplementation(libs.runner)
    // GrantPermissionRule: RECORD_AUDIO gates the microphone foreground service type, which the
    // recording service starts even when a test has swapped the recorder for a fake.
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
    // Compose UI / Hilt instrumented tests. androidTestImplementation does not extend
    // implementation, so the Compose BOM needs declaring again here.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    // Runtime permission dialogs are system UI, outside the Compose hierarchy.
    androidTestImplementation(libs.uiautomator)
    // Runs each instrumented test in its own process; see the testOptions comment above.
    androidTestUtil(libs.test.orchestrator)
    kspAndroidTest(libs.hilt.compiler)
}
