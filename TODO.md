# SDM Implementation TODO

Goal: Turn the current interface into a working download manager while preserving the approved design and testing on the phone.

Starting state: The interface and some interactions are implemented; downloads, files, and statistics use sample data. Tasks remain incomplete until implemented and verified against their acceptance criteria.

## Execution and progress tracking

- Work through stages 1–9 in order. The first delivery is at the end of stage 2.
- Preserve Open Design and the user's explicit design changes. Design and review any missing states before implementing them.
- `Always keep active`, automatic media detection/downloading, and YouTube-specific features are out of scope.
- After visual changes, compare device captures with the reference at the same logical viewport and have Astra review them as requested by the user.
- Check off a task only after its acceptance criteria are met. Record verification evidence or the report path beneath the task.

## 1. Core architecture and persistence

- [x] SDM-001 — Audit the current code and inventory all sample data, nonfunctional buttons, and disconnected settings.
  - Verification: [Source audit and implementation inventory](docs/SDM-001-audit.md).
- [x] SDM-002 — Define the download engine, repository, background service, and UI integration architecture; document the decisions.
  - Verification: [Architecture decisions](docs/architecture.md), based on the SDM-001 source audit.
- [x] SDM-003 — Define the download model: ID, URL, filename, MIME type, path, size, downloaded bytes, status, error, priority, and timestamps.
  - Verification: Download model and validation verified by the passing DownloadStateMachineTest suite.
- [x] SDM-004 — Define valid states and transitions: queued, connecting, downloading, paused, completed, failed, and cancelled; prevent conflicting operations.
  - Verification: DownloadStateMachineTest: 6 JVM tests passed, including invalid transitions, retry paths, completion requirements, and model validation.
- [x] SDM-005 — Implement the database and observable repository with migration support; restore records after process termination.
  - Verification: 5 SQLite device tests passed; separate seed → force-stop → verify instrumentation runs confirmed process-boundary persistence.
- [x] SDM-006 — Centralize access to existing settings without losing saved values.
  - Verification: 2 settings device tests passed: existing preference values preserved, updates persisted, and reset synchronized across consumers.
- [x] SDM-007 — Remove sample downloads, files, and statistics from the running app; display empty states matching the design.
  - Verification: Connected-device review passed for all three Downloads empty states, Files, zero download metrics, and actual storage values. Empty-state spacing and storage border insets corrected; renderer differences documented in [visual QA](docs/stage-1-visual-qa.md).

Acceptance: Data and settings survive relaunch, and the UI displays no fabricated downloads or statistics.

## 2. First real download

Depends on stage 1.

- [x] SDM-008 — Validate direct HTTP/HTTPS URLs in Add and display clear errors for invalid input.
  - Verification: 6 URL validation tests passed; debug build and lint passed; connected-device checks confirmed blank and unsupported-scheme errors, live error clearing, and valid HTTPS acceptance.
- [x] SDM-009 — Retrieve metadata while handling redirects, HTTP errors, and servers without HEAD or Content-Length support.
  - Verification: 21 MockWebServer JVM tests passed covering HEAD optimization, minimal Range fallback GET (bytes=0-0), 206 Content-Range total, 200 Range ignoring, zero body reads, nonnegative maxRedirects, redirects, cycles/limits, and response closure; assembleDebug and lintDebug passed.
- [x] SDM-010 — Extract and sanitize filenames from responses or URLs; prevent path traversal and accidental overwrites of existing files.
  - Verification: 27 filename resolver JVM tests passed covering RFC filename precedence and encoding, URL fallbacks, traversal and reserved names, 255-byte UTF-8 limits, deterministic collisions, and atomic reservation; all 60 unit tests, assembleDebug, and lintDebug passed.
- [x] SDM-011 — Connect Download to real record creation and transfer startup; prevent duplicate submissions from rapid taps.
  - Verification: coordinator tests passed for Download and Queue actions, concurrent duplicate suppression, atomic hidden temporary-file reservation, 255-byte filenames, rollback, and directory/I/O failures; Add now reports submission errors inline and starts the real transfer service.
- [x] SDM-012 — Stream downloads to temporary files without loading entire files into memory.
  - Verification: MockWebServer tests passed for multi-megabyte bounded-buffer streaming, monotonic persisted progress, HTTP failures, and deterministic mid-stream cancellation with partial-file preservation; all 74 unit tests, assembleDebug, and lintDebug passed.
- [x] SDM-013 — Calculate and display downloaded bytes, percentage, speed, and time remaining; handle unknown file sizes correctly.
  - Verification: progress-metrics tests passed for known, unknown, and zero sizes, zero elapsed time, completion, clamping, speed/ETA formatting, and overflow-safe ETA; existing card and status placeholders now show live values without layout changes; all 82 unit tests, assembleDebug, and lintDebug passed.
- [x] SDM-014 — Finalize files only after successful transfer; record failures and release resources on error paths.
  - Verification: transfer-engine tests passed for byte-for-byte finalization, known-length mismatch, unknown size, destination collision, missing destination, filesystem finalization failure, HTTP failure, and prompt cancellation; temporary files are preserved on recoverable failure and successful records become Completed only after finalization; all 87 unit tests, assembleDebug, and lintDebug passed.
- [x] SDM-015 — Connect live records and progress to Downloads cards; move successful downloads to Completed.
  - Verification: pure card-mapping and filter tests passed for queued, connecting, active known/unknown-size, paused, failed, and completed records; the selected tab filters immediately, completed records move out of Downloading into Completed, and existing card slots match the Open Design speed/ETA and queue copy; all 96 unit tests, assembleDebug, and lintDebug passed.
- [x] SDM-016 — Test on the phone using controlled URLs: small and large files, redirects, 404 responses, and unknown sizes; compare output sizes and checksums with the test source.
  - Verification: Connected-device acceptance matrix passed on physical device (Xiaomi 11T Pro, API 34). Verified byte-for-byte size and SHA-256 for small (64 KiB), large (4 MiB), redirect (128 KiB), and chunked (256 KiB) files, terminal GET 404 handling with 0-byte temp cleanup, collision resolution (`small (1).bin`), process-boundary persistence across force-stop, and inline HEAD 404 validation; all 97 unit tests, assembleDebug, and lintDebug passed. Documented in [SDM-016 Acceptance Report](docs/sdm-016-acceptance.md).

Acceptance / first delivery: Enter a real URL, see actual progress, and save an intact file on the phone. The result survives relaunch.

## 3. Background downloads

Depends on stage 2.

- [x] SDM-017 — Implement a foreground service appropriate for file transfers and tie its lifecycle to active downloads.
  - Verification: Application-context `startForegroundService` replaced process-local `applicationScope` launches; non-exported `dataSync` service enters foreground immediately, runs `DownloadTransferEngine` for distinct download ids, and `stopSelf(startId)` / cancels its scope only when the session is idle (`START_NOT_STICKY`). Command parse and session registry tests passed; `:app:testDebugUnitTest` 104 tests, `:app:assembleDebug`, `:app:lintDebug`, and `git diff --check` passed.
- [x] SDM-018 — Configure notification channels, notification permission handling, and clear behavior when permission is denied.
  - Verification: Runtime POST_NOTIFICATIONS is requested only on the first user-started Download (not Queue, not cold start); denied/unavailable still starts the dataSync foreground service; silent low-importance `sdm.transfer` channel; policy/channel unit tests passed with `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, and `git diff --check`.
- [x] SDM-019 — Show actual progress in notifications and open the corresponding download when tapped.
  - Verification: Live child notifications use the real filename, transferred/total bytes, determinate or indeterminate progress, and collision-safe download-id tags; summary and child taps route to Downloads or the exact record. Unit, assembly, lint, diff checks, and a connected-device instrumentation probe passed; the device notification showed `8.94 MB / 128.00 MB` at `6%` with a runnable content intent. The Downloads status card now reads real aggregate values without changing its design.
- [x] SDM-020 — Apply Keep active only when needed; release wake locks and resources on completion, failure, and pause.
  - Verification: Policy and wake-lock decision JVM tests passed with `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:lintDebug`. On a connected Android 14 device, a cleartext HTTP download held `PARTIAL_WAKE_LOCK sdm:keep-active` (`dumpsys power`); after the transfer failed, dumpsys logged `REL sdm:keep-active`, Wake Locks size=0, and the service stopped.
- [x] SDM-021 — Recover consistent state after process death; distinguish normal exit, removal from recent apps, force-stop, and device restart.
  - Verification: 9 focused recovery tests and the complete 156-test unit suite passed with `:app:assembleDebug`, `:app:lintDebug`, and `git diff --check`. On the connected Android 14 phone a seeded DOWNLOADING record recovered to FAILED after process restart while preserving `downloadedBytes=1234` and the actual 1234-byte partial file; the boot receiver and `RECEIVE_BOOT_COMPLETED` permission were registered; recovery does not start transfers.
- [x] SDM-022 — Test app exit, screen-off operation, and process recovery on the phone; record observed Android and device limitations.
  - Verification: Connected Xiaomi 2107113SG (11T Pro, Android 14/API 34) confirmed continued transfer after Back exit and screen-off/Dozing, FAILED recovery of a single row with preserved partial file after process death and force-stop, and documented platform limits; recents swipe was not measured. [Device lifecycle validation](docs/device-lifecycle-validation.md).

Acceptance: Transfers continue during normal app exit and screen-off operation. Interruptions do not corrupt files or state or create duplicate downloads.

## 4. Pause, resume, and queue

Depends on stage 3.

- [x] SDM-023 — Implement Pause by closing the connection, recording the offset, and preserving the temporary file.
  - Verification: User Pause cancels the HTTP call, waits for I/O to stop, and atomically persists the exact bounded `.part` length as `PAUSED` without failing or deleting the file; 170 JVM tests, assemble, lint, `git diff --check`, and 5/5 `SqliteDownloadRepository` instrumentation tests on Xiaomi Android 14 passed.
- [x] SDM-024 — Implement Resume using Range and validate Content-Range, ETag, or Last-Modified; prevent combining parts from different file versions.
  - Verification: Resume sends validated Range/If-Range, rejects missing or mismatched ETag/Last-Modified versions, appends only matching Content-Range bytes without silently accepting extras or mutating the original part on pre-append failure, and wires paused card/details Resume plus active Pause to the real transfer service; 47 focused tests, 196 JVM tests, assembleDebug, lintDebug, and git diff --check passed.
- [x] SDM-025 — Handle unsupported resume, HTTP 200 responses to Range requests, and HTTP 416 errors; restart clearly without corrupting files.
  - Verification: HTTP 200 to Range is a full restart body and HTTP 416 triggers one bounded non-Range GET onto a separate restart temp; progress and ETag/Last-Modified/total are replaced atomically, obsolete parts are removed only after success, cancellation keeps a usable new partial without mixing versions, and SHA-256 of the fresh body matches; 36 focused tests, 204 JVM tests, assembleDebug, lintDebug, and git diff --check passed.
- [x] SDM-026 — Implement Cancel, stop active work, and handle temporary files according to the approved deletion interaction.
  - Verification: 215 JVM tests passed with zero failures, assembleDebug and lintDebug passed, git diff --check passed, and the resulting debug APK installed successfully and launched into com.espitman.sdm/.MainActivity on the connected Xiaomi Android 14 device.
- [ ] SDM-027 — Connect Queue in Add to a real scheduler respecting priority and concurrent download limits.
- [ ] SDM-028 — Implement Download All and Pause All with correct handling of queued, active, and completed downloads.
- [ ] SDM-029 — Connect notification Pause, Resume, and Cancel actions to the same engine and shared UI state.
- [ ] SDM-030 — Test repeated pause/resume, rapid taps, concurrent downloads, and restart; verify output checksums.

Acceptance: Pause and resume do not duplicate downloads or corrupt files. Concurrency limits and queue priority are respected.

## 5. Complete Downloads and details integration

Depends on stage 4.

- [ ] SDM-031 — Connect Downloads search and filters to real data and preserve selection state.
- [ ] SDM-032 — Calculate actual active count, aggregate speed, remaining bytes, daily downloads, and connection count; handle unknown sizes.
- [ ] SDM-033 — Open each download's details by its ID; remove the dependency on a fixed sample file.
- [ ] SDM-034 — Connect the speed chart, HTTP information, and technical sections to actual measurements; represent unavailable values without fabricating data.
- [ ] SDM-035 — Implement Copy URL, Rename, and Move to top; keep filenames and records consistent.
- [ ] SDM-036 — Verify checksums when a valid reference checksum exists and show a clear state when none is available.
- [ ] SDM-037 — Show errors and Retry for network loss, timeout, expired links, and insufficient storage; bound automatic retries.
- [ ] SDM-038 — Check all Downloads and details actions and states on the phone, compare with the design, and obtain an Astra review.

Acceptance: Cards, statistics, details, and controls share real state, with no placeholder actions remaining.

## 6. Files and storage location

Depends on stages 2 and 5.

- [ ] SDM-039 — Choose a storage approach compatible with target Android versions and obtain folder access through standard system mechanisms.
- [ ] SDM-040 — Connect Save location to folder selection and persist permission; handle deleted folders and revoked access.
- [ ] SDM-041 — Display actual completed files in Files and implement filtering, search, and sorting according to the design.
- [ ] SDM-042 — Open and share files using secure URIs and correct MIME types; handle the absence of a compatible app.
- [ ] SDM-043 — Rename and delete real files while updating the database; handle files deleted outside the app.
- [ ] SDM-044 — Calculate actual used and free device storage and check capacity before transfers when file size is known.
- [ ] SDM-045 — Test folder selection, filename collisions, open/share/delete, and lost access on the phone.

Acceptance: Files displays real files only. File operations, storage locations, and permissions remain correct after restart.

## 7. Apply download settings

Depends on stages 4 and 6.

- [ ] SDM-046 — Enforce Wi-Fi only and pause/recover appropriately when network type changes.
- [ ] SDM-047 — Enforce aggregate speed limits, Unlimited, and Wi-Fi-specific limits; measure actual throughput.
- [ ] SDM-048 — Connect Auto-resume to network recovery and download state while respecting manual pauses.
- [ ] SDM-049 — Implement completion notifications and stalled-transfer alerts according to the settings toggles.
- [ ] SDM-050 — Implement segmented transfers only for suitable servers and files; fall back to a single connection when unsupported.
- [ ] SDM-051 — Connect Connections to segmented transfers with resource limits and safe segment recovery; verify final file integrity.
- [ ] SDM-052 — Define and document when each setting applies to active or future downloads; keep Preferences and Settings consistent.
- [ ] SDM-053 — Connect Reset to default values and the active engine while preserving files and data outside Reset's scope.
- [ ] SDM-054 — Test combinations of speed limits, network restrictions, concurrency, connections, and Auto-resume against selected values.

Acceptance: Every setting has a measurable effect, and displayed values match engine behavior.

## 8. Browser integration

Depends on stages 2, 4, and 6.

- [ ] SDM-055 — Complete address entry, navigation, back, and reload; show loading errors according to the design.
- [ ] SDM-056 — Implement tab management and a real tab counter matching the reference interaction.
- [ ] SDM-057 — Send user-selected download links to Add with the filename and necessary metadata.
- [ ] SDM-058 — Pass required cookies and headers securely for selected downloads without leaking credentials to unrelated redirect destinations.
- [ ] SDM-059 — Define and implement Private behavior for history, cache, cookies, and session termination; verify that the Private label is accurate.
- [ ] SDM-060 — Test browsing and user-selected downloads; verify that removed media and YouTube features have not returned.

Acceptance: A user-selected browser download goes through the shared Add flow and download engine.

## 9. Final testing and release preparation

Depends on the preceding stages.

- [ ] SDM-061 — Test multi-gigabyte files and concurrent downloads; check memory, speed, battery use, and file integrity.
- [ ] SDM-062 — Test network loss/recovery, Wi-Fi/mobile transitions, screen-off operation, process death, and reboot with partial downloads.
- [ ] SDM-063 — Test insufficient storage, revoked permissions, HTTP/TLS errors, and deleted files or folders; record recovery outcomes.
- [ ] SDM-064 — Test fresh installation and upgrades on target Android versions while preserving the database, settings, and files.
- [ ] SDM-065 — Review URL and filename handling, permissions, credential storage, and logs; remove sensitive data from error reports.
- [ ] SDM-066 — Compare all screens and real states in light/dark themes with the reference and obtain an Astra review; fix avoidable differences.
- [ ] SDM-067 — Run build, lint, and appropriate behavioral tests; document tested devices and remaining limitations.
- [ ] SDM-068 — Update README and documentation for download behavior, features, limitations, and build instructions.
- [ ] SDM-069 — Prepare release configuration and versioning; build and test the release package without committing signing keys.
- [ ] SDM-070 — Commit and push verified changes with a delivery report; publish the package publicly only if requested by the user.

Acceptance: The complete download and recovery lifecycle is tested on-device, output files are intact, no placeholder controls remain, and actual limitations are documented.
