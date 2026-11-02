# HEAD 403 / GET-signed URLs — 2026-11-03

The supplied endpoint returned HEAD 403, GET Range bytes=0-0 → 206 with total size 1,626,008,629, and ordinary GET → 200. Responses were closed after a single byte; the video was not downloaded. The source URL, credentials and signature are intentionally absent from source, tests and this report.

Metadata retrieval now probes GET after HEAD 403, just as for unsupported HEAD. It retains redirects/scoped headers and signed URL handling, closes response bodies, reports true GET denial, and discards failed HEAD error-page metadata. Synthetic regression tests cover preserved encoded query, 206 total length, ignored Range / 200, error-header isolation, and genuine HEAD+GET 403.

907 release unit tests: zero failures/errors/skips. Release lint and APK assembly pass. Version 0.2.81 (83), APK signature verified with existing development certificate. SHA-256: `099170b8076f051a78aa99f02076c9a8bf304555022b2fd79230bf299b95673d`. No connected device was available, so no on-device update is claimed.
