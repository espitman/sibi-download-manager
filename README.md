# Sibi Download Manager

An Android download-manager interface built with Kotlin and Jetpack Compose.

## Current state

The repository contains the first native Android implementation of the primary product screens:

- Downloads dashboard
- Private browser
- Downloaded files
- Settings
- Add-download sheet

The UI uses an embedded IBM Plex Sans font family. The font files are distributed under the SIL Open Font License included in `app/src/main/assets/licenses`.

The download engine and the remaining prototype interactions are still under development.

## Build

Requirements:

- Android Studio or Android SDK
- JDK 17

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

Run static analysis:

```bash
./gradlew :app:lintDebug
```

