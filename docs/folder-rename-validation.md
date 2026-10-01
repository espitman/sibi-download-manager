# Safe save-folder rename validation

Version: 0.2.70 (72). Device: Xiaomi 11T Pro, adb serial `30fe7f97`. Validation completed 2026-10-01.

## Behavior

Settings → Rename save folder uses the existing custom SettingsSheet. The app-specific Downloads folder and Android ExternalStorageProvider folders are supported. Other providers are rejected before mutation. Invalid names and existing sibling names are rejected. If an external provider unexpectedly chooses a suffixed name, the durable journal supports restoring the original name after regaining access.

Active workers stop before the rename; queued and active IDs are recorded for resumption. All verified segment files remain available and progress counts their actual combined bytes. Default/category references and download destinations are remapped with boundary checks; the database remap is transactional. Existing files are renamed with their parent directory, never deleted or copied. Manually paused downloads remain paused.

When Android invalidates the old tree grant, Grant access opens the actual new folder. Downloads remain paused until access is restored. The journal survives process death and recovery is idempotent; affected descendant tree grants may require their own selection. The app-specific folder needs no picker.

## Results

- 813 JVM tests passed, zero failures/errors/skips. Includes invalid-name cases, segment checkpoint resume requesting only remaining ranges, and revalidation of completed segment bytes.
- `:app:lintDebug`, `:app:assembleRelease` and `:app:assembleDebugAndroidTest` passed offline.
- `FolderRenameDeviceTest`: 5 tests passed on the physical device. Real 8 MiB HTTP transfer with two segments and a speed limit retained exactly the total bytes received across both segments. Resume produced the byte-identical final payload. Existing and untracked files, queue order, manual pauses, local name collision rejection, URI boundary remapping, process-death journal replay and denied-access state preservation passed.
- `FolderRenameSafDeviceTest`: both explicit phases passed with a real persisted Android grant and system picker between them. Renamed the isolated external folder, retained all active segment bytes, recovered access, updated category/default/queued/completed references, read the existing file and finished a byte-identical 8 MiB transfer through SAF.
- Real provider collision handling and reversal were exercised with isolated folders; the original name and files were recovered without clearing the pending journal prematurely.
- Device UI captured at 1080 × 2400 and compared with the rendered Open Design shared sheet at the matching logical viewport. The new requested form reuses its custom shell, gold border, handle, icon, spacing and buttons. The existing documented Avenir Next redistribution limitation remains; no new font substitution was introduced.
- Release APK signed with the existing local QA key and installed as an update. Original user folder selection (`Download/SDM`) restored; isolated test folders and test APK removed after validation.

## Limits

No removable SD card is available on this device. Cloud/custom document providers are deliberately unsupported rather than assuming stable document IDs. Public store publishing and production signing are outside this local release.
