# CLAUDE.md — Back Recorder

Android background audio recorder app with Google Drive cloud sync and in-app billing.

## Tech Stack

- **Language:** Kotlin (JVM 17)
- **UI:** Jetpack Compose + Material 3
- **DI:** Hilt (with KSP)
- **Database:** Room
- **Preferences:** DataStore
- **Background work:** WorkManager, foreground Service
- **Billing:** Google Play Billing Library
- **Cloud:** Google Drive API + Play Services Auth
- **Build:** Gradle with version catalog (`gradle/libs.versions.toml`)
- **Testing:** JUnit 4, MockK, Turbine, Espresso

## Project Structure

Single `:app` module. Package root: `com.backrecorderapp`.

```
data/
  billing/       — Google Play billing (BillingRepository)
  cloud/         — Google Drive sync (CloudSyncManager, DriveCloudStorage)
  db/            — Room database, DAO, entities
  preferences/   — DataStore wrapper (AppPreferences)
  segment/       — Audio segment management
  waveform/      — Waveform data for UI visualization
domain/          — Use cases (SaveRecording, TrimSegment, SubscriptionGate)
service/         — RecordingService (foreground), BootReceiver
ui/
  main/          — Main screen + ViewModel
  settings/      — Settings screen + ViewModel
  disclaimer/    — Disclaimer screen + ViewModel
  components/    — Reusable composables (WaveformView)
  navigation/    — NavGraph
  theme/         — Colors, typography, theme
di/              — Hilt AppModule
util/            — TimeUtils
```

## Build & Run

```bash
# Debug build
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run instrumented tests (requires device/emulator)
./gradlew connectedDebugAndroidTest
```

## Key Concepts

- **Segments:** Recordings are composed of segments — recording can be interrupted (e.g., phone call) and resumed.
- **Subscription gate:** Premium features (Drive sync, extended recording) are gated via `SubscriptionGate`.
- **Recovery:** Crash/interrupt recovery with a dedicated notification channel.
- **Three notification channels:** recording, cloud sync, recovery.

## Sensitive Files (do not commit)

- `google-services.json` — Firebase/Google config
- `*.jks` / `*.keystore` — signing keys
- `local.properties` — SDK paths
