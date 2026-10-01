# Configurable automatic retry validation

Release: 0.2.71 (73). Physical device: Xiaomi 11T Pro (`30fe7f97`), Android 14. Completed 2026-10-01.

## Behavior

Settings → Automatic retry uses the existing custom sheet and the same scroll-and-snap wheels as the time picker. Retries range from 0 through 10; 0 disables automatic retries. Delay is a fixed 1 through 300 seconds. Defaults are two automatic retries with a two-second delay. The original request is not included in the retry count.

Only temporary network failures, timeouts and HTTP 408/429/5xx failures qualify. Expired links, other HTTP errors, TLS trust failures and insufficient storage do not. Cards show the upcoming attempt and countdown; details show the countdown too. Pause, bulk Pause and Cancel prevent the pending attempt; partial progress is preserved. Manual Retry resets the automatic attempt budget.

Retries re-enter the shared queue and respect concurrency, Wi-Fi restrictions, download schedules and a pending folder migration. Failure time is stored separately from later record edits. Preferences and consumed attempts persist; changing the delay recomputes the deadline from the original failure time, and disabling or reducing the budget stops pending retries. Backup export/import includes settings and older backups retain the defaults.

The application collector handles awake timers and an Android alarm covers process recreation. Boot and clock-change reconciliation restore pending deadlines. Force-stopping an app blocks Android background work until it is opened again. When exact-alarm access is unavailable or the system throttles idle alarms, Android can deliver a retry late; it does not spend retry attempts while network access is disallowed.

## Verification

- 820 JVM tests passed with zero failures/errors/skips. Added policy coverage for exact budgets, disable, transient/permanent classification, changing delay after edits, manual Pause and bounds; card countdown/Pause rendering; old/new backup compatibility and invalid retry preferences.
- Offline release build, debug/test APK build and lint passed. Final wheel change reused the shared TimeWheel and keeps the latest selection callback so hour/minute and retry values do not capture stale state.
- 34 physical-device instrumentation tests passed: 5 automatic retry tests, 2 isolated settings tests and 27 SQLite repository regressions. Database migration suites ran against the matching debug variant because Kotlin internal helper names differ between debug and release; the final installed APK is release.
- A real local HTTP server returned two 503 responses, then a 4096-byte payload. The engine made exactly three requests, consumed two retries, and produced a byte-identical file through the normal queue.
- Exact budget exhaustion, no retries for 403/404, disabled retry, offline and migration gates, settings changes, manual Pause/Cancel, persisted deadlines after repository recreation, preference persistence/reset and migration from database version 11 passed.
- In the release app, staged one isolated waiting download, force-stopped/reopened the app, captured the card/details countdown and tapped Pause. Instrumentation verified PAUSED state, unchanged 128-byte progress and zero consumed attempts. Removed the fixture and restored the pre-test settings afterward.
- Captured final wheel sheet on the connected device at 1080 × 2400 and compared its common shell with the matching Open Design render. Wheel selection, Save and reopening persisted values were exercised. The new controls are user-requested and share the existing time-wheel component. The previously documented display-font licensing limitation remains.
- Signed the final release APK with the existing local QA key and installed it as an update without deleting user files or download records. Test APK and isolated fixtures were removed.

## Limits

Physical reboot and prolonged Doze delivery were not exercised. The connected device has no removable SD card. Public distribution and production signing are outside this local release.
