# SDM-016 Acceptance Test Report

## 1. Executive Summary

This report documents the physical device verification for **SDM-016** of the Sibi Download Manager (SDM) on Android.
All test scenarios were executed against a real, connected physical device using controlled HTTP fixture URLs served over `adb reverse` from a deterministic Python fixture server.

- **Physical Device**: Xiaomi 11T Pro (`2107113SG`, serial: `30fe7f97`, Android 14 / API 34, 1080×2400 @ 440 dpi)
- **Application ID**: `com.espitman.sdm` (Debug build)
- **Install Verification**: Freshly built `app-debug.apk` installed via `adb install -r`; package `lastUpdateTime=2026-09-21 19:55:31`.
- **Network Configuration**: Cleartext HTTP traffic explicitly enabled in `AndroidManifest.xml` (`android:usesCleartextTraffic="true"`); local port reverse mapping `tcp:8080 -> tcp:8080`.
- **Visual Compliance**: Open Design UI visuals and layouts preserved without modification. The final clean acceptance run stayed within the SDM application.
- **Result**: **ALL 8 ACCEPTANCE SCENARIOS PASSED ON DEVICE**.

---

## 2. Test Environment & Controlled Fixtures

A multi-threaded local HTTP fixture server (`server.py`) served controlled, deterministic byte sequences with explicit `Connection: close` and clean chunked framing.

| Endpoint | Size | SHA-256 Checksum | Characteristics |
|---|---|---|---|
| `GET /small.bin` | 64 KiB (65,536 B) | `7daca2095d0438260fa849183dfc67faa459fdf4936e1bc91eec6b281b27e4c2` | Fixed length, single-buffer transfer |
| `GET /large.bin` | 4 MiB (4,194,304 B) | `575f42009c28a230e4326c31b94954d2ec21c1a860cc14f40f045301c06eca45` | Multi-megabyte, throttled chunk streaming |
| `GET /redirect.bin` | 128 KiB (131,072 B) | `d0bddc6f3621577a6427757e63eb30b1de2a1f405333d6d3db657e9053ef0b70` | HTTP 302 Found redirect to `/target.bin` |
| `GET /chunked.bin` | 256 KiB (262,144 B) | `1f8b2f4ca2c23d6445b41ee34a28eae42f419778315a610b3438a61999e7cb75` | Unknown total size (`Transfer-Encoding: chunked`, no `Content-Length`) |
| `GET /notfound.bin` | N/A | N/A | HEAD 200 metadata, GET 404 terminal error |
| `HEAD /head404.bin` | N/A | N/A | Inline HEAD 404 error response on Add sheet |

---

## 3. Detailed Acceptance Scenario Matrix

| Scenario # | Test Scenario | Expected Outcome | Device Actual Outcome | Byte Count & Checksum Match | Status |
|:---:|---|---|---|---|:---:|
| **1** | **Small File** (`small.bin`) | Record created, progress shown, finalized file placed in download dir and card moves to Completed tab | Downloaded to `/files/Download/small.bin`, card rendered in Completed tab with 100% · 64.00 KB | **Exact Match**: 65,536 bytes<br>`7daca2095d0438260fa849183dfc67faa459fdf4936e1bc91eec6b281b27e4c2` | **PASS** |
| **2** | **Multi-Megabyte File** (`large.bin`) | Live progress and speed displayed in Downloading tab, moves to Completed tab upon finish | Live card displayed speed and percentage in Downloading tab; completed card displayed in Completed tab | **Exact Match**: 4,194,304 bytes<br>`575f42009c28a230e4326c31b94954d2ec21c1a860cc14f40f045301c06eca45` | **PASS** |
| **3** | **HTTP Redirect** (`redirect.bin` -> `target.bin`) | Redirect followed seamlessly, file finalized as `target.bin` | Followed 302, downloaded and finalized as `/files/Download/target.bin` | **Exact Match**: 131,072 bytes<br>`d0bddc6f3621577a6427757e63eb30b1de2a1f405333d6d3db657e9053ef0b70` | **PASS** |
| **4** | **Unknown / Chunked Length** (`chunked.bin`) | Streamed with "Unknown size" placeholder, finalized with exact byte total and moved to Completed | Card showed indeterminate/unknown size while downloading; finalized as `chunked.bin` (256.00 KB) in Completed | **Exact Match**: 262,144 bytes<br>`1f8b2f4ca2c23d6445b41ee34a28eae42f419778315a610b3438a61999e7cb75` | **PASS** |
| **5** | **Terminal GET 404** (`notfound.bin`) | Card transitions to "Failed" state in Downloading tab; no finalized file exposed; empty temp `.part` file removed | Card displayed "Failed" under Downloading tab; 0 finalized files created; 0 orphan `.part` files left | **Verified**: `/files/Download/notfound.bin` absent, 0 byte temp deleted | **PASS** |
| **6** | **Collision Handling** (Re-download `small.bin`) | Filename collision resolved by saving as `small (1).bin` without corrupting original `small.bin`; no stale `.part` files | Both `small.bin` and `small (1).bin` exist in download dir; both cards present in Completed tab | **Exact Match**: Both files 65,536 bytes with identical SHA-256; stale `.part` count = 0 | **PASS** |
| **7** | **Process Persistence** (`am force-stop` & relaunch) | All completed records and failed records survive process termination and appear correctly upon app relaunch | Relaunched cleanly; Failed card (`notfound.bin`) present in Downloading; all 5 completed cards present in Completed | **Verified**: SQLite database retained full state across process lifecycle | **PASS** |
| **8** | **Inline HEAD 404 Error** (`head404.bin`) | Add download sheet detects terminal HTTP error during URL preflight and displays inline error message | Sheet displayed inline error: `HTTP 404: Not Found`; transfer not queued | **Verified**: Exact error message `"HTTP 404: Not Found"` confirmed displayed on sheet UI | **PASS** |

---

## 4. Source & Unit Test Verification

### Engine Error Cleanup
To ensure terminal HTTP errors never leak empty placeholder `.part` files, `DownloadTransferEngine.kt` was verified to clean up 0-byte temporary files when `response.isSuccessful` is false.
A dedicated unit test was added to `DownloadTransferEngineTest.kt`:
- `httpTerminalFailureDeletesEmptyReservedPartFile()`: Verifies that an empty reserved `.part` file is deleted on terminal HTTP 404 while non-empty recoverable partial files continue to be preserved.

### Unit Test Suite
- **Command**: `./gradlew :app:testDebugUnitTest`
- **Result**: 97 tests completed, **0 failures**, **0 errors**.

### Static Analysis & Cleanliness
- **Command**: `./gradlew :app:lintDebug`
- **Result**: **BUILD SUCCESSFUL**, 0 lint errors.
- **Command**: `git diff --check`
- **Result**: Clean, 0 whitespace or formatting errors.

---

## 5. Artifacts and Photographic Evidence

All screenshots captured from the physical device during test execution are saved under `artifacts/actual/sdm-016/`:

1. `00_app_launched.png` — Freshly launched app state after `pm clear`.
2. `01_small_sheet_filled.png` — Add download sheet populated with `small.bin` URL.
3. `01_small_completed_tab.png` — Completed tab displaying `small.bin` (64.00 KB).
4. `02_large_sheet_filled.png` — Add sheet populated with `large.bin` URL.
5. `02_large_downloading_progress.png` — Downloading tab displaying active progress and speed for `large.bin`.
6. `02_large_completed_tab.png` — Completed tab displaying `large.bin` (4.00 MB).
7. `03_redirect_sheet_filled.png` — Add sheet populated with `redirect.bin` URL.
8. `03_redirect_completed_tab.png` — Completed tab displaying resolved `target.bin` (128.00 KB).
9. `04_chunked_sheet_filled.png` — Add sheet populated with `chunked.bin` URL.
10. `04_chunked_completed_tab.png` — Completed tab displaying finalized `chunked.bin` (256.00 KB).
11. `05_notfound_sheet_filled.png` — Add sheet populated with `notfound.bin` URL.
12. `05_notfound_failed_card.png` — Downloading tab showing `notfound.bin` in Failed state.
13. `06_collision_sheet_filled.png` — Add sheet with re-download URL for `small.bin`.
14. `06_collision_completed_tab.png` — Completed tab showing `small (1).bin` alongside `small.bin`.
15. `07_relaunch_downloading_tab.png` — Downloading tab after force-stop and relaunch showing persisted Failed record.
16. `07_relaunch_completed_tab.png` — Completed tab after force-stop and relaunch showing all 5 completed records intact.
17. `08_head404_inline_error.png` — Add sheet displaying inline error message for `head404.bin`.
