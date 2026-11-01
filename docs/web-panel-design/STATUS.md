# SDM web panel — completed, 2026-11-01

The web-panel implementation and its reopened acceptance cases are complete. Implementation 77a1ec5 and final acceptance fixes 991202c are pushed to origin/main. The original phone app is updated; the panel is open in external Chrome at http://10.22.10.45:8081/ with its existing approval.

## Delivered behavior

Phone-hosted Ktor and bundled assets/fonts; configurable port/start/stop; phone approval/code and browser revocation; shared HTTP/torrent/magnet queue, selection and group actions; live details/charts, schedules/limits/retry; Files, safe rename/move/delete and previews; storage/category-folder permission handoffs; checksum, protected-link refresh and backup review/restore. The in-app Browser is excluded as requested. Downloads and torrent processing run on the phone.

The acceptance pass found and fixed two production issues: interrupted torrent-folder copies lacked a durable pre-copy intent, and libtorrent exempted LAN peers from its global rate class. Recovery now removes only verified owned copies and retains user changes. Both IPv4/IPv6 peers obey the shared limit, applied before resume. Shared native colors and Avenir Next/Plex typography are matched to the source; fixed-column and custom-control behavior is retained.

## Final validation

- Release: 892 unit tests, zero failures/errors/skips; lintRelease and assembleRelease pass. Production JavaScript: 60/60.
- Physical process kill during a real copy write and after directory rename: recovery passes (.117/.329 s); exact source/selected bytes and original record remain. Changed interrupted copy is retained (.153 s). Four existing move/provider regressions pass (.596 s).
- Bounded SAF destination genuinely exhausts its quota and returns ENOSPC during writing: preservation/cleanup passes (.238 s). The physical phone was not filled.
- Concurrent real HTTP/torrent 64/32 MiB transfers under shared 1 MB/s then live 2 MB/s: both make progress and finish with exact SHA-256 (31.720 s). Measured aggregate 1.042/1.957 MB/s; IPv4/IPv6 class regression passes (.841 s).
- Genuine saved-network transition 10.22.10.45 → 10.22.4.106 → original network with both downloads active: authenticated listener at both addresses, same approved API credential, no pending auto-approval, retained offsets and exact completion (41.511 s). Original Wi-Fi/settings/listener restored.
- Final original-app installed-release checksum/backup/torrent selection/link identity/replay regressions: four tests pass (1.960 s). Actual Chrome retains All 20 / Queue 4 / Completed 16 and both actual browser approvals without pairing again.
- All 21 approved state comparisons are reconciled. Forty-five fresh phone-served example captures have no horizontal overflow, including 320/390 px; final installed LAN view is 390/390 px with correct Copy address. Actual operation captures remain distinct from read-only visual examples. No automated whole-image zero-pixel-difference score is claimed.
- Local graph refreshed with graphify update .; no global graph update. The other user's emulator was not used or stopped.

## Installed package and scope

Xiaomi 2107113SG; app com.espitman.sdm 0.2.75, non-debuggable, signed using the existing local QA/debug certificate (not a Google Play signing-key claim). Original main-target test runner restored; disposable QA app removed. Port 8080 belongs to another service, so SDM remains on 8081.

Approved credentials remain valid through network changes. Browser cookies follow browser origin rules; automatically migrating a cookie between different IP origins is not claimed. The physical changed-IP acceptance uses the same valid API credential at both addresses and actual Chrome recovery after restoring its original origin.

## Evidence

- Root DOWNLOAD_MANAGER_FEATURES_TODO.md, section 10: all web-panel items checked.
- [Portable results and log hashes](verification/final-2026-11-01/acceptance-results.json).
- [Detailed verification and prior operation evidence](verification/README.md).
- [Native/web capability mapping](verification/capability-matrix.md).
- [21-state comparison](verification/design-state-matrix.md).
- [Final installed Downloads](verification/final-2026-11-01/installed-downloads-desktop.png) and LAN desktop/mobile captures in the same folder.
