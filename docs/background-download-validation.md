# Background transfer regression — 2026-11-04

User reports minimizing SDM stops an active download after a short time and displays “Next in queue”. Exact device cause has not been reproduced; only another user's emulator is connected and was not used, per request.

Confirmed code weaknesses: the foreground download service returned START_NOT_STICKY, process recovery was only triggered by MainActivity/boot, and active CPU protection depended on the retired keepActive setting, which is forced false. Those are implementation findings, not proof of the OEM event causing the report.

Changes in 0.2.82 (84): accepted foreground service starts return START_STICKY; a null-intent Android restart invokes the shared once-per-process interruption recovery before queue observation. Recovered queued starts keep the service alive while their start commands are dispatched. Manual pauses, finished downloads, blocked folder mutations and future schedules do not create idle keep-alive work. A bounded, renewable PARTIAL_WAKE_LOCK is now automatic for CONNECTING/DOWNLOADING and released when no transfer remains active or service teardown begins. No keep-active option or always-on behavior is restored.

Validation: 912 release unit tests, zero failures/errors/skips; release assembly and lint pass. New tests cover recovered queue dispatch without Activity startup, shared recovery gate, saved bytes/manual pause, scheduled idle service shutdown and active-transfer CPU protection/release. Existing timeout renewal and teardown decision tests continue to pass. This does not prove physical service restart/minimize/screen-off behavior.

APK signature verified with the existing development key. Desktop candidate: /Users/espitman/Desktop/APKs/SDM-0.2.82.apk. SHA-256: a2b5aed1b7d3b95bd257db2a2fa02e9eb7fdaa4f18d1888c4c56230ce0bcd4be.

Physical acceptance remains open in DOWNLOAD_MANAGER_FEATURES_TODO.md. Version 0.2.82 was not installed or published as the latest GitHub release, pending that device check. The public latest release remains 0.2.81.
