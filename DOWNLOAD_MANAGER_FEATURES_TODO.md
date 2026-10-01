# Download Manager Feature Backlog

This checklist covers the requested gaps compared with other Android download managers. Implement and verify each item separately.

## 1. Scheduled downloads

- [x] 01. DMF-001 — Define one-time and recurring download schedules, including start time, end time, and time zone behavior.
- [x] 02. DMF-002 — Persist schedules and restore them after app restart or device reboot.
- [x] 03. DMF-003 — Enforce schedules in the queue without interrupting manual pause, Wi-Fi-only rules, or the simultaneous-download limit.
- [x] 04. DMF-004 — Add schedule controls and visible scheduled states using SDM's existing design language.
- [x] 05. DMF-005 — Test exact-time start, out-of-window pause, reboot recovery, clock/time-zone changes, and user overrides.

## 2. Refresh expired download links

- [x] 06. DMF-006 — Allow a new HTTP/HTTPS URL to replace an expired URL on an unfinished download.
- [x] 07. DMF-007 — Verify that the new URL identifies the same file before reusing downloaded bytes; otherwise offer a safe restart.
- [x] 08. DMF-008 — Preserve the download's queue position, destination, and visible progress when safe, and show clear failure feedback.
- [x] 09. DMF-009 — Test signed-URL expiry, changed content, redirects, partial files, and pause/resume after replacement.

Verification: Added single and selected-download refresh sheets, newline import, unique filename matching with manual assignment, strong ETag/checksum identity checks, range support checks, explicit per-file restart confirmation, and atomic URL persistence with partial-file rollback. Queue order, priority, destination, and safe progress are preserved. Active transfers pause before checks. Completed records cannot be refreshed. JVM tests cover expiry, redirects, weak/changed identity, restart, and byte-for-byte range continuation; a release-targeted device test covers real SQLite persistence, partial preservation, restart, pause/resume, and repository reopening. Grouped checking and manual assignment were exercised on the phone without replacing user links.

## 3. Per-download speed limits

- [x] 10. DMF-010 — Persist an optional speed limit for each download alongside the existing global limit.
  - Verification: database v11 nullable column and repository read/write implemented; release-target device persistence/reopen/pause-resume/unlimited test passed on 30fe7f97.
- [x] 11. DMF-011 — Apply global and individual limits together without exceeding either one or distorting displayed speed.
  - Verification: JVM transfer test passes both limiters and verifies output bytes and progress; existing segmented transfer and global limiter tests pass.
- [x] 12. DMF-012 — Add individual limit controls to download details, matching the existing SDM sheet style.
  - Verification: release 0.2.64 device screenshots reviewed; applying 500 KB/s, reopening the sheet and returning to Unlimited passed.
- [x] 13. DMF-013 — Test concurrent downloads, runtime limit changes, pause/resume, and unlimited mode.
  - Verification: release-target physical-device test passed with two concurrent 8 MiB files, two connections each, isolated SQLite data and real HTTP range transfers. Observed combined 250.01 KB/s under 250 KB/s global cap; live file reduction yielded 40.00 KB/s, retained after pause/resume; individual Unlimited retained the 120 KB/s global cap (119.999 KB/s observed). Removing all limits completed both files byte-for-byte. Existing JVM and persistence tests also pass.

## 4. Link import/export and backup

- [x] 14. DMF-014 — Import newline-separated HTTP/HTTPS links from a user-selected text file, with duplicate and invalid-line feedback.
- [x] 15. DMF-015 — Export selected or all download links to a user-selected file without leaking private request headers or cookies.
- [x] 16. DMF-016 — Export and restore the download list and settings with a versioned backup format and conflict handling.
- [x] 17. DMF-017 — Test malformed files, large lists, partial failures, duplicate entries, and backup compatibility.

Verification: [Physical-device and automated validation](docs/import-export-backup-validation.md). Release 0.2.69 installed and tested; malformed restore, duplicate handling, real document export/import, share chooser and isolated SQLite rollback passed.

## 5. Automatic folders by file type

- [x] 18. DMF-018 — Let the user map file categories to save folders through Android's document-tree picker.
- [x] 19. DMF-019 — Classify files using trusted metadata and filename fallback, then reserve the correct destination before download.
- [x] 20. DMF-020 — Handle revoked folder permissions and unknown types without losing or misplacing downloads.
- [ ] 21. DMF-021 — Test category rules, filename collisions, SD-card folders, and changes to rules while a download is active.

Verification: [Physical-device and automated validation](docs/automatic-folders-validation.md). Real HTTP-to-SAF transfer, byte equality, collision handling, persisted rules, simulated revoked grants and rule edits during transfer passed. DMF-021 remains open only for a physical removable-SD-card check; no such volume is available on the connected device.

## 6. Configurable automatic retry

- [x] 22. DMF-022 — Add settings for retry count and delay while retaining safe defaults.
- [x] 23. DMF-023 — Apply the retry policy only to transient failures; never auto-retry manual pauses or permanent errors.
- [x] 24. DMF-024 — Show the next retry clearly and let the user pause or cancel during the delay.
- [x] 25. DMF-025 — Test exhausted retries, connectivity loss, process restart, and settings changes during a retry cycle.

Verification: [Configurable automatic retry validation](docs/automatic-retry-validation.md).

## 7. Browser authenticated downloads

HTTP Basic authentication is excluded from the plan at the user’s request.

- [x] 26. DMF-026 — Let users explicitly choose whether to use and retain the browser session for a download.
- [x] 27. DMF-027 — Preserve necessary authenticated browser request context for eligible downloads across pause/resume and app restart, with explicit privacy controls.
- [x] 28. DMF-028 — Handle authentication failure and expired sessions with a clear reauthentication path.
- [x] 29. DMF-029 — Test redirects, credential scope, private browsing, restart, and accidental credential disclosure.

Verification: [Browser session validation](docs/browser-session-validation.md).

## 8. Torrent and Magnet downloads

- [ ] 30. DMF-030 — Choose and integrate a maintained Android-compatible BitTorrent engine; document its license and storage implications.
- [ ] 31. DMF-031 — Accept magnet links and `.torrent` files, show metadata, and allow selection of contained files before starting.
- [ ] 32. DMF-032 — Implement torrent progress, pause/resume, queue integration, persistence, and safe cleanup.
- [ ] 33. DMF-033 — Add torrent controls and states in SDM's visual style, with clear distinction from HTTP downloads.
- [ ] 34. DMF-034 — Test magnet metadata resolution, multi-file torrents, connectivity changes, restart recovery, and storage exhaustion.

## Release verification

- [ ] 35. DMF-035 — Run unit/integration tests and regression checks for existing HTTP downloads, browser handoff, queue actions, and storage.
- [ ] 36. DMF-036 — Compare every new visible control and state against the Open Design reference and verify on a connected device.
- [ ] 37. DMF-037 — Produce a release build and document remaining limitations only after the requested features are complete.

## Product boundaries

- Proxy support is excluded by request.
- Do not restore Always keep active, automatic media detection/downloading, or YouTube-specific access.
- Keep the existing private-browser behavior unless a later request explicitly changes it.

## 9. Interactive app tutorial

- [ ] 38. DMF-038 — Design a complete guided tour in SDM's visual style, using highlighted controls and short explanations with Next, Back, Skip, and progress indicators; obtain approval for the visual design before implementation.
- [ ] 39. DMF-039 — Introduce the tour on first launch, remember completion or dismissal, and provide a Restart tutorial entry in Settings.
- [ ] 40. DMF-040 — Guide users through Downloads: aggregate status, All/Queue/Completed, adding single or multiline links, clipboard paste, download/queue actions, pause/resume, selection, bulk actions, deletion choices, and single/group queue reordering.
- [ ] 41. DMF-041 — Guide users through download details: progress, live speed, connections, history chart, technical information, request headers, segments, rename, checksum verification, priority, copy URL, open folder, scheduling, expired-link refresh, and individual speed limits including the orange card indicator.
- [ ] 42. DMF-042 — Guide users through Browser: address entry and history suggestions, navigation, normal/private browsing, history, bookmarks, tabs, eligible download handoff, and applicable site controls.
- [ ] 43. DMF-043 — Guide users through Files and Settings: file sorting and actions, save-location access and permissions, connection/simultaneous-download settings, network restrictions, notifications, global speed limits, global scheduling, appearance, and every additional shipped feature from this checklist.
- [ ] 44. DMF-044 — Use isolated sample downloads and tutorial state for hands-on steps; never start real downloads, modify existing files or settings, open external file managers, or request permissions without a clear user action. Restore the user's view when the tour ends.
- [ ] 45. DMF-045 — Make highlights follow their actual controls while navigating and scrolling; support small screens, keyboard visibility, accessibility, interruption/relaunch, and missing or disabled controls. Keep one shared tutorial behavior across screens and sheets.
- [ ] 46. DMF-046 — Audit coverage against every shipped feature and interactive control; test the full tour, skip/restart, sample-state cleanup, accessibility, and existing-app regressions on the connected device before release.

## 10. Rename the save folder safely

- [x] 47. DMF-047 — Add a Rename save folder action in SDM's existing sheet style; rename the selected folder and preserve access to existing files.
- [x] 48. DMF-048 — Update affected default/category folder references and download destinations safely, including queued and active downloads; preserve progress and prevent stale destinations.
- [x] 49. DMF-049 — Handle unsupported providers, permission loss and name collisions without changing or losing data; test existing files, queued downloads and active transfers on the connected device.

Verification: [Safe folder rename validation](docs/folder-rename-validation.md).
