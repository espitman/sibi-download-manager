# SDM web panel — delivery status, 2026-11-01

The core web-panel implementation is installed and working on the connected phone. The remaining unchecked device qualifications are listed explicitly below; they are not represented as completed tests. Implementation commit `77a1ec5` is pushed to origin/main; the final documentation reconciliation follows in a documentation-only commit.

- Phone: Xiaomi 2107113SG, serial `30fe7f97`.
- Panel: `http://10.22.10.45:8081/`, opened in external Chrome with its existing approval.
- Port 8080 is occupied by another service. SDM's native bind-error message worked; only SDM's port was changed to 8081.
- Final installed release: 0.2.75, non-debuggable, signed with the existing local QA/debug certificate. This is not a Google Play signing-key claim.
- Original state remains 20 downloads, Queue 4 / Completed 16. No existing download/file was deleted or resumed for the final UI check.
- Disposable `com.espitman.sdm.qa` / `.qa.test` removed. Six obsolete test-browser credentials removed; both actual Chrome approvals retained. The other user's emulator was not used or stopped.

## Implementation

Phone-owned Ktor transport, static bundled assets/fonts, authenticated approval/code/session flows, configurable port, native access/file handoffs, HTTP/torrent/magnet submissions and selected-file transfers, queue/group actions, HTTP/torrent details, file management/previews, schedules/limits/retry/network preferences, storage/category folders/rename, checksum/link refresh and backup review/restore use shared native repositories and coordinators. The in-app Browser remains excluded as requested.

The last capability audit found the missing details Open folder shortcut. It now reveals the HTTP file in Files, opens a torrent folder, or explains that an incomplete file appears after completion. Actual installed Chrome navigation/selection passes. Corrected mobile long destination-path wrapping and actual LAN-address grid overflow; both views remain exactly 390 px wide at a 390 px viewport.

## Verification

- 892 JVM tests, zero failures; lintRelease and release assembly pass: `/tmp/sdm-final-release-nov01.log`.
- 60 production-JS checks pass: `/tmp/sdm-final-js-nov01.log`, including revoked-owner draft cleanup, expired review cleanup and read-only HTTP/torrent/incomplete Open folder routing.
- Final installed release checksum, backup/torrent round trips and link-identity/restart/replay regressions: `/tmp/sdm-final-installed-release-device.log`, OK (4 tests), 1.615 seconds. Original record guards pass.
- Actual Chrome bulk automatic/manual matching, independent stale result, metadata 404 and explicit restart/exact bytes: `/tmp/sdm-chrome-links-bulk-pass.log`, OK (1 test), 40.873 seconds.
- Actual two-minute visible link-review expiry preserves record/64 KiB partial bytes: `/tmp/sdm-chrome-links-expiry.log`, OK (1 test), 173.85 seconds. The expired green result/apply cleanup added afterward passes a JS regression.
- Actual Chrome backup duplicate/corrupt-replacement/settings/category restore: OK (1 test), 299.646 seconds; owner/expiry mutation-gate and rejection regressions: OK (7 tests), .585 seconds. Restored unavailable category warning API additionally verified: `/tmp/sdm-category-unavailable-final.log`, OK (1 test), .055 seconds.
- Prior real native folder grants/decline/cancel/expiry/rename recovery, Open/Share recipient grants, file previews/menu behavior, native approval/code/revocation, ordinary-command concurrency, daily alarms, shared HTTP rate changes, real-peer torrent transfers, tab closure, Wi-Fi/cellular and process recovery remain valid. Exact scopes/logs are in verification/README.md.
- All 21 board-state comparisons reconciled in verification/design-state-matrix.md. Apply native monogram/fonts, larger reading-scale and control overrides from v1/README.md. No automated zero-pixel-difference score is claimed.
- Local graph refreshed with `graphify update .`; no global graph operation.

## Extended qualification not performed

A physically different LAN IP/network while a request is pending; real OS termination in the middle of folder-copy publication; filling the phone to physical ENOSPC; a fresh simultaneous HTTP+torrent aggregate-rate benchmark. Existing same-IP/cellular/process recovery, controlled provider-write failures/staged journal recovery, real two-HTTP aggregate rates and mixed-protocol allocation source review do not claim these additional scenarios.

## Evidence and source

- Checklist: root DOWNLOAD_MANAGER_FEATURES_TODO.md.
- Operation logs/captures: verification/README.md.
- Source parity: verification/capability-matrix.md.
- Board comparisons: verification/design-state-matrix.md.
- Final screenshots: `panel-final-lan-desktop-2026-11-01.png`, `panel-final-lan-mobile-2026-11-01.png`, `panel-final-phone-2026-11-01.png`, `open-folder-revealed-release-2026-11-01.png`.

Final external Chrome view remains open on Downloads: All 20 / Queue 4 / Completed 16. Local worktree delivery is committed; no emulator was touched.
