# SDM-063 recovery QA matrix

Focused JVM coverage for insufficient storage, revoked folder access, HTTP/TLS failures, and files or folders deleted outside the app. Connected-device contract tests cover SAF behavior and SQLite persistence on the Xiaomi Android 14 phone.

| # | Scenario | Stimulus | Recovery outcome | Auto-retry | JVM evidence |
| --- | --- | --- | --- | --- | --- |
| S1 | Fresh known-size transfer, too little free space | Capacity probe reports fewer bytes than the object | `FAILED` / `Not enough storage`. No body bytes written. Empty reserved `.part` left unused. Destination absent. | No | `insufficientStorageFailsWithoutWritingThenManualRetryCompletes` |
| S2 | Manual retry after space is freed | Same record after `retryFailed(automatic=false)` with sufficient capacity | Re-queues, transfers, `COMPLETED`. Destination SHA-256 matches the source. `.part` removed. | n/a | same |
| S3 | Mid-stream `ENOSPC` | First durable chunk then `No space left on device` | `FAILED` / `Not enough storage`. Partial `.part` kept. Destination not finalized. | No | `midStreamEnospcKeepsPartialAndDoesNotAutoRetry` |
| S4 | Revoked user-folder grant at publish | SAF tree inspects as `PermissionRevoked` after a successful local finalize | File is promoted to app-specific Downloads. Record loses the tree URI. Save location falls back to the app folder. | n/a | `revokedTreeGrantPublishesLocallyAndClearsTheSaveLocation` |
| S5 | HTTP 403 | Terminal GET 403 | `FAILED` / `Link expired`. Empty reserved `.part` deleted. Destination absent. | No | `http403DoesNotAutoRetryWhile503RecoversOnTheNextAttempt` |
| S6 | Transient HTTP 503 then 200 | First GET 503, automatic retry, then 200 | One automatic re-queue. `COMPLETED` with matching checksum. | Yes, cap 2 | same |
| S7 | TLS handshake failure | HTTPS transfer whose `SSLSocketFactory` rejects the handshake (`Trust anchor…`) | `FAILED` / `Download failed`. Persisted `SSLHandshakeException`. No HTTP request observed. Destination absent. | No | `tlsHandshakeFailureStaysFailedWithoutAutoRetry` |
| S8 | Deleted `.part` before retry | Record still has `downloadedBytes > 0`, temp file missing | Offset aligns to 0. Full GET (no Range). `COMPLETED` with matching checksum. | n/a | `deletedPartRestartsFromZeroAndMatchesChecksum` |
| S9 | Truncated `.part` | On-disk prefix shorter than persisted offset | Offset aligns to the real prefix. Range `bytes=<prefix>-` with If-Range. `COMPLETED` with matching checksum. | n/a | `truncatedPartResumesFromTheRemainingBytes` |
| S10 | Deleted destination folder | Parent directory removed before finalize | Engine recreates the folder. `COMPLETED`. | n/a | `deletedDestinationFolderIsRecreatedOnFinalize` |
| S11 | Deleted completed file vs revoked access | One missing local file, one readable, one SAF `AccessUnavailable` | Only the confirmed-missing row is pruned. Revoked access keeps the record. | n/a | `deletedCompletedFilesArePrunedWhileRevokedAccessIsKept` |
| S12 | Pause then missing `.part` | `DownloadResumePart` fails, then manual Retry | Resume path records `Incomplete download part is missing`. Retry starts from zero and completes. | No on the missing-part failure | `pausedResumeWithDeletedPartFailsThenManualRetryRestarts` |
| S13 | Complete `.part`, but destination parent replaced by a file | Publish fails after all bytes arrived; user fixes the parent and retries | Complete `.part` remains and is published on retry without a second HTTP request. | No | `DownloadTransferEngineTest.missingDestinationDirectoryBlockedByFileKeepsDownloadedPartForRecovery` |

Classifier notes (no new UI labels):

- Insufficient storage phrases, including `ENOSPC` and `No space left on device`, stay `Not enough storage`.
- HTTP 401/403/410 stay `Link expired`. 408/429/5xx stay `Temporary server error`. Other 4xx stay `HTTP error`.
- Handshake, certificate, pinning, and cleartext-TLS messages stay `Download failed` (`OTHER`) even when the detail also mentions I/O or `timed out`. They do not consume the automatic retry budget.

## Production fixes in this pass

1. **Deleted or truncated partials.** `DownloadTransferEngine` now aligns `downloadedBytes` to the on-disk `.part` length before Range or progress updates. Without this, contract-faithful repositories rejected the first progress write (`Download progress cannot move backwards`) and left a retryable transfer `FAILED`.
2. **TLS classification.** SSL exceptions persist their type name. Certificate/handshake phrases are classified before timeout and generic network I/O so `SSL handshake timed out` does not auto-retry.
3. **Publication retry.** A fully downloaded `.part` whose destination publish failed can be finalized after the folder is repaired without requiring a Range validator or a second transfer.
4. **`alignDownloadedBytes` default.** The interface default always fails. A no-op-when-already-aligned default would hide missing persistence until a deleted or truncated `.part` needed a write.

## Connected-device verification

- `StorageAccessRegressionTest` and `CompletedFileDocumentsContractTest`: 11 passed on the Xiaomi Android 14 phone. These use an instrumented DocumentsProvider to exercise tree access, revoked access, fallback, and completed-file operations without modifying the user's folders.
- `SqliteDownloadRepositoryTest.shorterPartialAlignmentPersistsAcrossRepositoryRecreation`: passed on the same phone. A persisted offset was lowered after a simulated partial-file truncation, survived repository recreation, and allowed subsequent progress.
- The debug APK and AndroidTest APK were installed with `adb install -r`, preserving app data. No device reboot or storage-filling operation was performed.

## Remaining device checks

The following end-to-end conditions remain unmeasured on physical storage/network services: real storage exhaustion during a write, revocation of a user-selected tree through system settings, and a live untrusted HTTPS certificate. Their deterministic JVM and instrumented-provider counterparts are covered above. No device data was deliberately filled or permission changed for these checks.

No Always-keep-active, automatic media, or YouTube paths were added.
