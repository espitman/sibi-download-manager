# Release validation

## SDM-064: installation and upgrade

Tested on 2026-09-23 with an isolated Android 16 (API 36) ARM64 emulator and a Xiaomi 2107113SG running Android 14 (API 34). Both devices installed the `versionCode=2`, `versionName=0.2.0` debug APK using `adb install -r` for upgrades. No app data was cleared on the phone.

| Device and path | Evidence | Result |
| --- | --- | --- |
| Fresh install on a newly created API 36 AVD | The package was absent before installation. The app launched to an empty Downloads state with 0 B downloaded and no records. | Pass |
| API 36 upgrade from commit `144b550` (`versionCode=1`) to current `versionCode=2` | Before upgrading, a 32,768-byte HTTP fixture completed and matched SHA-256 `e11360251d1173650cdcd20f111d8f1ca2e412f572e8b36a4dc067121c1799b8`. The version 9 database held one `COMPLETED` row and `auto_resume=false` was stored. After `install -r` and relaunch, the row, setting, database version, file length, and SHA-256 were unchanged. | Pass |
| API 34 phone upgrade from `versionCode=1` to `versionCode=2` | The four existing download records had the same identity digest before and after installation; the settings XML digest was unchanged. Files still showed all four completed records after relaunch. | Pass |

API 26 is the declared minimum SDK, but no API 26 system image was installed locally. An attempted SDK image download was too slow to complete during this validation; API 26 runtime behavior remains unmeasured. All 26 `SqliteDownloadRepositoryTest` instrumentation cases passed on the connected API 34 device, including migration paths from database versions 1 through 8. The current database version is 9.

The phone was not rebooted or rotated. Its sleep and rotation settings were not changed.

## SDM-065 and SDM-066: security and visual review

The [security review](sdm-065-security-review.md) removed URL, token, credential, host, and path leaks from persisted/displayed error text, tightened filename and WebView handling, and excluded the URL-bearing database from backup. Its 145 focused JVM tests passed. HTTP without TLS remains supported.

The [visual QA report](sdm-066-visual-qa.md) records comparisons at 393 × 873 in dark and light themes, including the four main screens, Browser tabs, Settings theme sheet, Add sheet, Completed cards, and download details. Astra reviewed the implementation and identified the safe-area, tab-sheet, status-label, selected-row, and no-op control mismatches that were fixed. Avenir Next display glyphs remain different because no redistribution license is available.

## SDM-067 and SDM-069: final build and device matrix

The final build command `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease :app:bundleRelease --offline` passed. The complete JVM suite has 654 tests and zero failures after a final focused rerun. Intermittent failures in a reliability fixture were traced to two test-only assumptions: an `AtomicInteger.updateAndGet` lambda incremented a counter even though it may execute more than once, and a fully written paused part was expected to issue an unnecessary final HTTP Range request. Both fixture assertions were corrected without changing production transfer behavior.

| Environment | Final checks | Result |
| --- | --- | --- |
| Xiaomi 2107113SG, Android 14 / API 34, 393 × 873 dp | Preserved-data debug upgrade; live screen comparison; targeted SQLite migrations, packaged permissions, and Browser WebView instrumentation | 28/28 device tests passed. Four existing downloads and settings remained intact. |
| Isolated Android 16 / API 36 ARM64 emulator | Fresh install, v1→v2 preserved-data upgrade, twelve targeted instrumentation classes, release-package install and relaunch | 61/61 device tests passed. The QA-signed release package showed both completed records after installing over debug without clearing data. |
| Release package on API 36 | Non-debuggable `0.2.0` / versionCode 2 APK, signed with the local Android debug identity only for QA; live direct HTTP download | Downloaded a second 32,768-byte file into a collision-safe name. SHA-256 `e11360251d1173650cdcd20f111d8f1ca2e412f572e8b36a4dc067121c1799b8` matched the source. `apksigner verify` passed v2 and v3 signature checks. |

Release build artifacts are `app/build/outputs/apk/release/app-release-unsigned.apk` (SHA-256 `84e1da373aa6a09febf834272217ab351de64bdb960e4d22ed5bee97f0195aff`) and `app/build/outputs/bundle/release/app-release.aab` (SHA-256 `4cf1ed921d6441d9c1121b871f3e138faa0fb6f04af08e054950228c9fc5649f`). The local QA-signed APK at `/tmp/sdm-release-qa.apk` has SHA-256 `ab2e8301ad83f1a64578b7243503fc96f42dab355c564aba34e12ca5258d4445`; it is not a distribution artifact or production signature. No signing key or package artifact is committed.

Runtime testing on API 26 remains outstanding. The physical phone was not rebooted, rotated, or disconnected during final QA. Unplugged battery drain, actual physical storage exhaustion, system-side SAF grant revocation, and a live untrusted TLS certificate remain unmeasured; their focused logic tests and earlier recovery matrix are in [SDM-063 recovery QA](sdm-063-recovery-qa.md). A public package has not been published.
