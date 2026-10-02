# Torrent validation — 0.2.73

Build: `versionCode=75`, minimum API 26, target API 35. Physical device: Xiaomi 11T Pro, Android 14, ARM64 (`30fe7f97`). A separate ARM64 emulator was also used. All fixture files, databases and native peers belong to the tests; public swarms and existing user downloads were not used as fixtures.

## Automated checks

- `testDebugUnitTest`: 832 tests, zero failures. Includes magnet/path validation, portable backup encoding and existing HTTP transfer, queue, retry, browser and storage coverage.
- Seven torrent device scenarios passed: real local-peer magnet metadata; selected-file payload with byte equality; manual and network-policy pause/resume with native-session restart and seeding on/off; malformed metadata/selection; insufficient local capacity without truncation; provider write exhaustion followed by successful publication without duplicate copies; `.torrent` file/HTTP resolution and cancelled magnet cleanup; portable native-metadata backup restoration with conflict handling and rollback. Pause/resume and seeding share one scenario, as do file/HTTP/cancellation.
- Five initial torrent scenarios plus two additional metadata/backup scenarios passed on the physical phone. The final emulator run passed the seven scenarios together with 33 repository/document regression tests. Three host-gated procedures are skipped during a normal suite run.
- Abrupt process-death recovery passed separately on the phone: stage a partial native transfer in an isolated database, force-stop SDM without a graceful engine shutdown, start a fresh process, requeue the interrupted record and compare the finished selected file byte-for-byte. The two phase procedures require `torrentPhase=stage` and `torrentPhase=recover`.
- 53 focused document/storage, SQLite, backup and browser-session/handoff regressions passed. Two older storage expectations were updated to respect the already implemented configurable app-folder name instead of assuming Android's literal `Download` directory.
- Final debug/test/release compilation and `lintDebug` passed. Native libraries ship for all four declared ABIs; ARM64 and x86_64 ELF load segments have 16 KiB alignment. The signed APK passed `apksigner verify` and `zipalign -c -P 16 4`.

## Phone UI and preservation

HTTP torrent metadata was resolved on the phone, contained-file checkboxes were exercised, and only the selected 1 MiB file was added to the shared queue. Torrent info showed the same selected file, live peer/seed/upload fields and working Pause/Resume. The fixture was paused and deleted through the production incomplete-delete coordinator. A separately gated test accepts only the explicit fixture UUID, expected filename and ownership marker.

A database snapshot comparison before and after the work found exactly the same 17 original IDs, states, downloaded/total byte counts, destinations, names, priorities and queue orders. The final non-debuggable release APK was installed as an update with the existing local QA signing identity, preserving app data. Torrent metadata resolution was exercised again in that release.

The Open Design reference was rendered with a 393 × 873 logical content viewport and compared with physical-phone captures at the equivalent viewport. The shared settings sheet retains the reference's 16 dp side inset, 22 dp radius, 17 dp inner padding, 44 dp icon/close containers and `#17181A` panel surface. Torrent-specific states have no dedicated screen in the Open Design HTML; the authorized torrent functionality uses these existing custom sheet primitives and SDM controls. Existing screens were not replaced with generic Material layouts.

Cropped evidence excludes unrelated user filenames:

- [Reference settings sheet](torrent-validation/reference-settings-sheet.png)
- [Torrent input](torrent-validation/phone-add-torrent.png)
- [Contained-file selection](torrent-validation/phone-torrent-contents.png)
- [Torrent info and resume](torrent-validation/phone-torrent-info.png)

## Artifact and limits

Local QA artifact: `SDM-0.2.73-release.apk`, SHA-256 `42bd7be2a802211ad2cf080cb5e8b9836b5b2b06b4c9ccc4cd8ee4d4314f4227`. It is signed for the existing local test installation; distribution signing remains the separate release process documented in [release preparation](release.md).

See [torrent behavior and licenses](torrent-downloads.md) for metadata/backup bounds, private-magnet availability, provider staging space and opt-in seeding. No removable SD-card volume or 16 KiB-page device is connected; packaging/alignment was checked, but runtime coverage for those environments is not claimed. The older removable-card checklist item remains open.
