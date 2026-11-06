# Back Recorder

Android background audio recorder with Google Drive sync and in-app billing.

Kotlin 17 · Jetpack Compose/M3 · Hilt (KSP) · Room · DataStore · WorkManager · Play Billing · Google Drive API · Gradle version catalog (`gradle/libs.versions.toml`) · Tests: JUnit 4, MockK, Turbine, Espresso, Compose UI test (instrumented)

Single `:app` module. Package root: `com.backrecorderapp`.

## Key Concepts

- **Segments:** Recordings are composed of segments — recording can be interrupted and resumed.
- **Recording state:** `RecordingService` exposes static `isRunning: StateFlow<Boolean>`. UI observes this directly — no DataStore pref. `AppPreferences.wasRecordingBeforeShutdown` is only for crash-recovery restart.
- **Segment flushing:** `SaveRecordingUseCase` → `RecordingFlusher.flushCurrentSegment()` finalizes the in-progress segment (writes MP4 MOOV atom) for `MediaExtractor`. Flusher is a Hilt interface backed by `RecordingService.flushCurrentSegment()` (static, `CompletableDeferred`). Rotation guarded by `Mutex`.
- **Minute-snapped selection:** Waveform handles snap to minute boundaries (start floors, end ceils) for drags and time-preset chips. End handle clamps to `bufferEndTime` so the currently-recording minute stays reachable.
- **WaveformView touch:** Custom `awaitEachGesture` pointer-input loop (not `detectHorizontalDragGestures`). Dedicated handle zones above/below waveform; 2D distance disambiguates overlapping handles.
- **Billing:** `BillingRepository` checks subscription status (FREE/ACTIVE) only. Purchases happen via Play Store, not in-app.
- **Subscription gate:** Premium features (Drive sync, extended recording) gated via `SubscriptionGate`. Every entry point that starts cloud sync must pass it — including the app-launch resume in `BackRecorderApp`, not just the settings toggle.
- **Drive auth:** `DriveAuthManager` owns the OAuth grant via the Play Services Authorization API (`Identity.getAuthorizationClient`). `GoogleSignIn`/`GoogleAccountCredential` are **not** available — removed in play-services-auth 22 — so don't reach for them. It caches the access token for 45 min, re-authorizes silently, and force-refreshes on 401. `grantGeneration` changes whenever the grant does; anything caching account-scoped Drive state (folder ids) must compare it rather than rely on callers to invalidate.
- **Drive folder layout:** `BackRecorder/staging` holds segments; `recovery_<timestamp>` folders are siblings under `BackRecorder/`, never inside `staging`. Anything inside `staging` is deleted once it ages past the 24h buffer, so a recovery folder placed there takes its contents with it.
- **Notifications:** Three channels — recording, cloud sync, recovery.
- **Instrumented tests:** `testInstrumentationRunner` is `com.backrecorderapp.HiltTestRunner`, which swaps in `HiltTestApplication` — the real `BackRecorderApp` connects billing and resumes cloud sync on create, which no test should do. Compose UI tests use `@HiltAndroidTest` + `HiltAndroidRule` + `createAndroidComposeRule<MainActivity>()`; `ComposeHarnessSmokeTest` is the reference. Seed preferences in a rule ordered *between* the Hilt rule and the compose rule, not in `@Before` — the compose rule launches the activity before `@Before` runs, and `NavGraph` fixes its start destination from `disclaimerAccepted` on first composition. CI runs only `:app:testDebugUnitTest`, so `androidTest` needs an emulator (`./gradlew :app:connectedDebugAndroidTest`).
- **`BootReceiver` and Hilt:** `BootReceiver` resolves `AppPreferences` through `EntryPointAccessors`, **not** `@AndroidEntryPoint`. Under instrumentation `HiltTestApplication`'s component is built by `HiltAndroidRule`, so a manifest-registered `@AndroidEntryPoint` receiver that catches `BOOT_COMPLETED` before the first test aborts the whole run. Any new manifest-registered receiver needs the same treatment.

## Sensitive Files (do not commit)

`google-services.json`, `*.jks`/`*.keystore`, `local.properties`, `.claude/.creds`
