# Sibi Download Manager (SDM)

SDM is a native Android download manager built with Kotlin, Jetpack Compose, OkHttp, and a versioned SQLite repository. It accepts direct HTTP and HTTPS links, saves real files, and keeps the approved Open Design interface for Downloads, Browser, Files, Settings, and Add. Android 8.0 (API 26) is the declared minimum; the target SDK is 35.

## Downloads

- Add a direct link with **Download** or **Queue**. SDM validates the URL, follows bounded redirects, retrieves metadata when available, chooses a safe filename, and reserves a destination without overwriting existing files.
- Transfers stream to temporary files, show live progress and speed, then publish the final file only after successful completion. Unknown sizes are supported.
- A foreground service continues active transfers after leaving the app. Pause, Resume, Cancel, Download All, and Pause All use the same persistent repository and scheduler. Resume validates server range and version evidence; unsupported resume restarts safely.
- Priority, simultaneous-download count, per-download connections, Wi-Fi only, aggregate speed limits, Auto-resume, completion notifications, and stalled-transfer alerts are applied according to [the settings behavior matrix](docs/download-settings-behavior.md).
- The Downloads cards, filters, search, statistics, and details show real records. Details include available HTTP/resume evidence, progress history, file actions, and failure information. Missing measurements are shown as unavailable.

HTTP links without TLS are intentionally supported. SDM does not automatically discover or download media, and has no YouTube-specific handling.

## Files and Browser

Completed files appear in Files when their storage entry is readable. Files supports search, type filters, sorting, secure Open/Share intents, Rename, and Delete. The default save location is app-specific storage; a user-selected folder uses Android's system folder picker and persisted Storage Access Framework grant. If a folder or grant disappears, SDM reports the loss and falls back safely for future downloads.

The in-app private Browser supports address entry, navigation, reload, tabs, and explicit download-link handoff to Add. A selected link can carry same-origin cookies and headers into its download; redirecting to another origin strips those credentials. Browser session data is cleared on exit. JavaScript and DOM storage remain enabled for ordinary pages, while file/content access is disabled.

## Build and test

Use JDK 17 and an Android SDK with API 35 installed. From the repository root:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

For connected-device tests, build the test APK and run the required instrumentation classes with `adb shell am instrument`; avoid a blanket run because several suites deliberately manipulate storage and app state. The [release validation report](docs/release-validation.md) lists the devices and suites used. The Open Design prototype is the source for visual and interaction checks.

The release variant is `0.2.5` (`versionCode=7`). See [release preparation](docs/release.md) for unsigned artifacts, local QA signing, and distribution signing. No signing key belongs in this repository.

## Current limits

The minimum API 26 runtime has not yet been tested on a device because its emulator image was unavailable locally. Physical reboot recovery, actual battery drain during unplugged large transfers, live device storage exhaustion, system-side SAF grant revocation, and a live untrusted TLS certificate were not measured; the relevant logic has focused automated coverage and the precise outcomes are recorded in [release validation](docs/release-validation.md) and [recovery QA](docs/sdm-063-recovery-qa.md). URLs needed for resume remain on the device in the download database, but are excluded from automatic backup and error reports. Browser cookies can survive an abrupt process death until the private session is next cleared.

The interface embeds IBM Plex Sans under its [SIL Open Font License](app/src/main/assets/licenses/ibm_plex_sans_ofl.txt). The reference uses Avenir Next for some display text; it is not bundled because no redistribution license is present.

Implementation progress and per-task evidence are in [TODO.md](TODO.md).
