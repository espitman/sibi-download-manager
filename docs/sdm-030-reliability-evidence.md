# SDM-030 reliability evidence (JVM)

Focused integration tests for repeated pause/resume, rapid commands, concurrent scheduling, and process-death recovery. They drive the real `DownloadTransferEngine`, `DownloadTransferSession` commands, `DownloadQueueScheduler`, `DownloadInterruptionRecovery` / `DownloadRecoveryOnceGate`, contract-faithful repository mutations, `.part` / `.restart.part` paths, and MockWebServer Range handling. No connected-device run was performed.

## Scenarios

### 1. Repeated pause/resume (`repeatedPauseResumeFiveCyclesCompletesOneRecordWithMatchingChecksum`)

- Payload: 98,304 bytes, `byte[i] = (i % 251).toByte()`.
- Repeat count: **5** pause/resume cycles, then a final resume through `ResumeTransferCommand` → `DownloadQueueScheduler.resume` → session `StartTransferCommand`.
- Each cycle parks at the first engine chunk boundary at or past the next 16,384-byte barrier (`onChunkRead` after the write, then `ensureActive`).
- Asserts: one row/id; persisted offset equals `.part` length and never regresses or exceeds total; Range `bytes=<prior-length>-` with If-Range ETag; destination SHA-256 matches source; COMPLETED size/timestamps coherent; `.part` and `.restart.part` absent.

Source SHA-256: `f39e9f45bf8c7f0acf2b3ec3c812290a6d97f47b5606780cdbd728c348e54758`

### 2. Rapid taps (`rapidDuplicateCommandsKeepOneJobAndCancelWinsPauseRace`)

- 24 concurrent duplicate `ResumeTransferCommand` / `StartTransferCommand` / `schedule()` calls behind a coroutine barrier while one transfer is parked.
- Then concurrent Pause vs Cancel; Cancel wins (`DownloadTransferSession` cancel flag, then `cancelAtExactOffset`).
- Extra Cancel is idempotent. Peak overlapping jobs per id is 1. One start. Terminal `CANCELLED`.

### 3. Concurrent downloads (`concurrentTransfersHonorLimitPriorityAndIsolatedChecksums`)

- Limit **2**, queued **4**. Priority then creation order: `high-late`, `mid-early`, then refill `mid-late`, `low`.
- Starter instrumentation: `peakActiveTransfers == 2` while two are `DOWNLOADING` and two remain `QUEUED`; peak stays 2 through completion.
- Each 24,576-byte payload is salted by Java `String.hashCode()` of the id. Output SHA-256 matches that id’s source; files do not mix.

| id | SHA-256 |
| --- | --- |
| low | `f9a5e6b2b88ae88b8a7ac489b89cb2b6efae66d62ccd4d24cd2cbe893d3ab916` |
| mid-early | `e0a6eb2c85b2a3c2c3080373869050a0df3ef80204e1f70a684186146a5213ca` |
| mid-late | `ef97b55e258c26043e9c369a5f810a77b20f98c984f525e92e3d8c3ae519a1d0` |
| high-late | `15b55e711bedb3d439ee2c62e1da81f3001cdbdba73124a32d579264169dcd16` |

### 4. Restart/recovery (`processDeathRecoveryDoesNotCompleteAndResumeMatchesChecksum`)

- Interrupt a real partial without Pause (`killActive` + chunk barrier) to leave `DOWNLOADING`/`CONNECTING` and a `.part` file.
- Fresh `DownloadRecoveryOnceGate` + `DownloadInterruptionRecovery.recover(..., PROCESS_RESTART)` runs **once** under 6 concurrent callers; state is `FAILED` with the process-restart error, not COMPLETED.
- Explicit requeue: `DownloadQueueScheduler.downloadAll()` (FAILED is not `resumePaused`). Resume Range starts at the preserved part length; validators apply; final SHA-256 matches; temps gone.

Source SHA-256: `5533d8f72c15bb5e4c9a97cfae504f141991e8d8a7fc29771c8d3c48e253b08a`

## Focused commands / results

```
./gradlew :app:testDebugUnitTest \
  --tests com.espitman.sdm.download.DownloadReliabilityEvidenceTest \
  --tests com.espitman.sdm.download.DownloadTransferEngineTest \
  --tests com.espitman.sdm.download.DownloadPausePersistenceTest \
  --tests com.espitman.sdm.download.DownloadQueueSchedulerTest \
  --tests com.espitman.sdm.download.DownloadInterruptionRecoveryTest \
  --tests com.espitman.sdm.download.DownloadTransferSessionTest
```

**BUILD SUCCESSFUL.** Entire JVM suite, lint, assemble, install, and device UI were not run.

## Production change

`DownloadTransferEngine` invokes `onChunkRead` after the chunk is written and then `ensureActive()`, so pause/kill barriers observe durable bytes and cooperative cancel cannot append another chunk after the barrier. No scheduler, session, or recovery behavior bug required a logic fix.

## Files

- `app/src/main/java/com/espitman/sdm/download/DownloadTransferEngine.kt`
- `app/src/test/java/com/espitman/sdm/download/DownloadReliabilityEvidenceTest.kt`
- `app/src/test/java/com/espitman/sdm/download/DownloadReliabilityFixtures.kt`
- `docs/sdm-030-reliability-evidence.md`

## Limitations

- JVM/MockWebServer only. Occupancy is asserted from the real scheduler starter (`peakActiveTransfers` and repository occupying states). MockWebServer `dispatch()` can overlap more than two threads while answering headers; that is not treated as extra transfer slots.
- Pause/recovery barriers are the first completed read chunk at or past a planned offset (exact 4 KiB alignment is not guaranteed by OkHttp’s `InputStream.read`).
- No connected-device evidence for process death, notifications, or UI.
