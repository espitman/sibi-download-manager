# Stage 1 verification

Scope: SDM-001 through SDM-007. Implementation introduces persistent records and settings; HTTP transfer implementation remains stage 2.

## Implementation map

| Task | Implementation / evidence |
| --- | --- |
| SDM-001 | `SDM-001-audit.md`: source inventory of sample data, no-op actions, and disconnected settings. |
| SDM-002 | `architecture.md`: domain/repository/UI boundaries, application ownership, persistence, and future coordinator/service responsibilities. |
| SDM-003 | `domain/Download.kt`: immutable records, optional size/MIME/destination/HTTP validators, timestamps, validation. |
| SDM-004 | `domain/DownloadStateMachine.kt`: explicit transitions, invalid-operation rejection, completion/error requirements. |
| SDM-005 | `data/DownloadDatabase.kt`, `SqliteDownloadRepository.kt`, `DownloadRepository.kt`, `AppRepositories.kt`: versioned SQLite, migrations, serialized IO, observable state, application-scoped ownership. |
| SDM-006 | `data/settings/SettingsRepository.kt`: same preference file and legacy keys; observable shared settings used by Settings, Theme, Preferences, Keep active, and Speed limit. |
| SDM-007 | Downloads and Files observe repository records. Sample record lists and unused legacy Downloads UI removed. Actual device storage comes from StatFs. Existing reference empty-state layout and strings reused. |

## Behavior boundaries

- New installs begin with no download records. Relaunch reads SQLite instead of recreating sample downloads.
- Empty Downloads shows zero counts/bytes/connections. Metrics requiring a future engine use an unavailable marker when populated rather than fabricated speed, connections, or daily traffic.
- Files renders completed records and real storage capacity; it does not claim arbitrary files were verified.
- Keep active and speed settings are now durable. Cancel discards sheet edits; Save/Apply commits them. Their transfer effects still belong to later tasks.
- Transfer actions do not mutate sample state or claim success. The retained details prototype is unreachable until the per-record details integration in stage 5; no sample details are presented at runtime.
- Add still does not start a download. Stage 2 implements that path.
- No automatic media downloading, YouTube-specific access, or Always keep active mode was introduced.

## Validation results

- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug` passed.
- Six JVM state-machine/model tests passed.
- Five SQLite instrumentation tests passed on the connected Android 14 phone: reopen persistence, non-destructive v1-to-v3 migration, invalid-transition rollback, serialized conflicting transitions, and stale-progress/overwrite rejection.
- Process persistence passed using separate seed and verify instrumentation invocations, with the app and test process force-stopped between them. The test uses an isolated database and cleans up its records.
- Two settings instrumentation tests passed: preservation of legacy values and durable updates, plus observable reset across consumers.
- Latest debug APK installed on the connected phone. All three Downloads empty states and Files were captured and reviewed against the reference at the same logical viewport and matching light theme; the user's theme was preserved. See [visual QA](stage-1-visual-qa.md) for scope, artifacts, and pre-existing deviations.
- Device dashboard showed zero download metrics. Files showed actual storage values (190.91 GB used of 224.18 GB; 33.28 GB available; 85% used at capture time), replacing the prototype constants.

## Reproducing the checks

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.espitman.sdm.data.SqliteDownloadRepositoryTest,com.espitman.sdm.data.settings.SettingsRepositoryTest com.espitman.sdm.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.espitman.sdm.data.ProcessPersistenceTest -e persistencePhase seed com.espitman.sdm.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop com.espitman.sdm.test
adb shell am force-stop com.espitman.sdm
adb shell am instrument -w -e class com.espitman.sdm.data.ProcessPersistenceTest -e persistencePhase verify com.espitman.sdm.test/androidx.test.runner.AndroidJUnitRunner
```

Select the target with `adb -s <serial>` when more than one device is connected. The process test intentionally skips without its phase argument; a normal aggregate instrumentation run does not replace the two-phase check.
