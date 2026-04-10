# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Project: BackRecorder

Android application that records continuously, stores the recordings into 1 minute blocks and rotate them after a certain time.
The user can save any portion of these blocks into a single continuous file to save something the phone has recorded earlier.


## Build Commands

```bash
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK (ProGuard minification + resource shrinking)
./gradlew test                   # Unit tests
./gradlew connectedAndroidTest   # Instrumentation tests (requires connected device/emulator)
./gradlew test --tests "com.backrecorderapp.ExampleUnitTest"  # Single test class
```

Build output goes to `G:\temp\gradle_build` (configured in `gradle.properties`).

## Architecture

The app uses **MVVM with layered services**, structured as:

```
UI (Jetpack Compose / Material3)
    ↓
MainActivity (orchestrates service binding, auth flows, subscriptions)
    ↓
Foreground Services ←→ Helpers/Managers
    ↓
WorkManager Workers
```

### Key Data Flows

**Recording flow**: `MainActivity` binds to `RecordingService` (foreground service) via local binder. UI state flows up via `StateFlow`. Audio is recorded at 16kHz mono / 32kbps into the app's external cache dir (`/Android/data/.../cache/audio_recordings`).

**Save/export flow**: `SaveRecordingService` (separate foreground service) handles exporting — either via `ACTION_CREATE_DOCUMENT` intent for local save, or Google Drive upload for premium users.

**Subscription flow**: `SubscriptionManager` exposes a `StateFlow<SubscriptionMode>` (FREE / TRIAL / PREMIUM). `BillingManager` wraps Google Play Billing 8.0. `TrialManager` tracks trial periods. All subscription checks go through `SubscriptionManager`.

**Google Drive auth**: `GDriveHelper` handles OAuth2 via AppAuth library. Credentials are stored encrypted using Tink (`SecurePreferences`). App settings persist via DataStore (`SettingsDataStore`).

### Package Structure

```
com.backrecorderapp/
├── MainActivity.kt         # Entry point, service binding, permission handling
├── helpers/                # GDriveHelper, SubscriptionManager, BillingManager,
│                           # TrialManager, SettingsDataStore, SecurePreferences,
│                           # RecordingConfig, AudioUtils, TimeHelper
├── services/               # RecordingService, SaveRecordingService
├── ui/                     # Compose screens and components
└── workers/                # TrialReminderWorker (WorkManager)
```

## Tech Stack

- **Language**: Kotlin 2.0.0, Java 11 target
- **UI**: Jetpack Compose + Material3
- **Min SDK**: 31, Compile SDK: 36
- **Key libs**: Google Play Billing 8.0, AppAuth 0.11.1, Tink 1.12.0, OkHttp 4.12.0, WorkManager 2.11.1, DataStore 1.1.6
- **Dependency versions**: managed via `gradle/libs.versions.toml`

## Coding Rules

- **Subscription checks must go through `SubscriptionManager`** — never call `BillingManager` directly.
- **All UI state via `StateFlow`** — no LiveData, no direct UI mutation from services.
- **Threading**: standard Android conventions — `Main` for UI, `IO` for disk/network, `Default` for CPU-bound work.

## Testing

- Instrumentation tests require a **real device** (`connectedAndroidTest`). Do not assume an emulator is available.
- Lint: Android lint only (`./gradlew lint`).

## Debugging

- Logcat tag convention: `TAG = "ClassName"` per class/service.

## Release

- Release keystore is stored **outside the repository**. Do not expect it to be present in the project directory.
- Release build: `./gradlew assembleRelease` (ProGuard minification + resource shrinking enabled).
