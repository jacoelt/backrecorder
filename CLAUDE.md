# Back Recorder

Android background audio recorder with Google Drive sync and in-app billing.

Kotlin 17 · Jetpack Compose/M3 · Hilt (KSP) · Room · DataStore · WorkManager · Play Billing · Google Drive API · Gradle version catalog (`gradle/libs.versions.toml`) · Tests: JUnit 4, MockK, Turbine, Espresso

Single `:app` module. Package root: `com.backrecorderapp`.

## Key Concepts

- **Segments:** Recordings are composed of segments — recording can be interrupted and resumed.
- **Recording state:** `RecordingService` exposes static `isRunning: StateFlow<Boolean>`. UI observes this directly — no DataStore pref. `AppPreferences.wasRecordingBeforeShutdown` is only for crash-recovery restart.
- **Segment flushing:** `SaveRecordingUseCase` → `RecordingFlusher.flushCurrentSegment()` finalizes the in-progress segment (writes MP4 MOOV atom) for `MediaExtractor`. Flusher is a Hilt interface backed by `RecordingService.flushCurrentSegment()` (static, `CompletableDeferred`). Rotation guarded by `Mutex`.
- **Minute-snapped selection:** Waveform handles snap to minute boundaries (start floors, end ceils) for drags and time-preset chips. End handle clamps to `bufferEndTime` so the currently-recording minute stays reachable.
- **WaveformView touch:** Custom `awaitEachGesture` pointer-input loop (not `detectHorizontalDragGestures`). Dedicated handle zones above/below waveform; 2D distance disambiguates overlapping handles.
- **Billing:** `BillingRepository` checks subscription status (FREE/ACTIVE) only. Purchases happen via Play Store, not in-app.
- **Subscription gate:** Premium features (Drive sync, extended recording) gated via `SubscriptionGate`.
- **Notifications:** Three channels — recording, cloud sync, recovery.

## Sensitive Files (do not commit)

`google-services.json`, `*.jks`/`*.keystore`, `local.properties`
