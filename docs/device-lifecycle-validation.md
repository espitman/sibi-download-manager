# SDM-022 device lifecycle validation

Connected-device measurements for app exit, screen-off transfer, and process recovery. Pause/resume is not implemented until SDM-023/024.

## Device and source

- Device: Xiaomi 2107113SG (11T Pro), Android 14 / API 34.
- Controlled local HTTP source through `adb reverse`, independent of VPN/internet.
- Test data is restored/removed separately by supervisor.
- Temporary adb stay-awake setting was reverted: `stay_on_while_plugged_in=0` and `mStayOn=false`.

## Measured cases

### Normal Back exit

Database progress 15,990,784 → 20,250,624 bytes in 6 seconds (+4,259,840). State stayed `DOWNLOADING`. Foreground service stayed active.

### Screen explicitly off / Dozing

Database progress 8,978,432 → 12,058,624 bytes in 8 seconds (+3,080,192). State `DOWNLOADING`. Foreground service active. `PARTIAL_WAKE_LOCK sdm:keep-active` held.

### Forced process death

Before kill: DB checkpoint 1,507,328 bytes; partial file 1,572,864 bytes. Process and service stopped. `START_NOT_STICKY` did not restart the service.

Next launch recovered exactly one row to `FAILED`, stored 1,572,864 bytes, kept the partial file at 1,622,016 bytes, and did not start a service or duplicate the row.

### Force-stop

Process and service remained absent until explicit user launch. Then exactly one row recovered `FAILED`, stored 1,572,864 bytes, and retained a 1,589,248-byte partial file.

## Observed limitations

- Bounded checkpoint gap: the physical partial file may be ahead of persisted `downloadedBytes` by up to the 64 KiB progress interval when the process dies. Recovery intentionally marks `FAILED` and preserves the file because pause/resume is not implemented until SDM-023/024.
- Android 14 rejected starting a foreground service from a background debug receiver. Starting from the foreground Activity succeeded. This is an expected platform restriction; the real Download flow starts from foreground UI.
- MIUI recents swipe could not be automated reliably after keyguard re-locked. Normal Activity exit was measured directly. Recents removal was not measured.

## Result

Acceptance is met for the measured cases: transfers continued through normal Back exit and screen-off/Dozing; process death and force-stop did not corrupt records, duplicate rows, or auto-restart work.
