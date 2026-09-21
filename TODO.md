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

- [ ] SDM-008 — Validate direct HTTP/HTTPS URLs in Add and display clear errors for invalid input.
- [ ] SDM-009 — Retrieve metadata while handling redirects, HTTP errors, and servers without HEAD or Content-Length support.
- [ ] SDM-010 — Extract and sanitize filenames from responses or URLs; prevent path traversal and accidental overwrites of existing files.
- [ ] SDM-011 — Connect Download to real record creation and transfer startup; prevent duplicate submissions from rapid taps.
- [ ] SDM-012 — Stream downloads to temporary files without loading entire files into memory.
- [ ] SDM-013 — Calculate and display downloaded bytes, percentage, speed, and time remaining; handle unknown file sizes correctly.
- [ ] SDM-014 — Finalize files only after successful transfer; record failures and release resources on error paths.
- [ ] SDM-015 — Connect live records and progress to Downloads cards; move successful downloads to Completed.
- [ ] SDM-016 — Test on the phone using controlled URLs: small and large files, redirects, 404 responses, and unknown sizes; compare output sizes and checksums with the test source.

Acceptance / first delivery: Enter a real URL, see actual progress, and save an intact file on the phone. The result survives relaunch.

## 3. Background downloads

Depends on stage 2.

- [ ] SDM-017 — Implement a foreground service appropriate for file transfers and tie its lifecycle to active downloads.
- [ ] SDM-018 — Configure notification channels, notification permission handling, and clear behavior when permission is denied.
- [ ] SDM-019 — Show actual progress in notifications and open the corresponding download when tapped.
- [ ] SDM-020 — Apply Keep active only when needed; release wake locks and resources on completion, failure, and pause.
- [ ] SDM-021 — Recover consistent state after process death; distinguish normal exit, removal from recent apps, force-stop, and device restart.
- [ ] SDM-022 — Test app exit, screen-off operation, and process recovery on the phone; record observed Android and device limitations.

Acceptance: Transfers continue during normal app exit and screen-off operation. Interruptions do not corrupt files or state or create duplicate downloads.

## 4. Pause, resume, and queue

Depends on stage 3.

- [ ] SDM-023 — Implement Pause by closing the connection, recording the offset, and preserving the temporary file.
- [ ] SDM-024 — Implement Resume using Range and validate Content-Range, ETag, or Last-Modified; prevent combining parts from different file versions.
- [ ] SDM-025 — Handle unsupported resume, HTTP 200 responses to Range requests, and HTTP 416 errors; restart clearly without corrupting files.
- [ ] SDM-026 — Implement Cancel, stop active work, and handle temporary files according to the approved deletion interaction.
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
