# Automatic folders verification

Release 0.2.69 (versionCode 71), Xiaomi 2107113SG / Android 14, USB serial 30fe7f97, 2026-10-01.

## Physical-device checks

- Opened the six-category overview and Video folder editor. Selected the isolated Download/SDM-Feature-QA tree through Android DocumentsUI and persisted read/write access.
- Saving and reopening preserved the chosen rule. Returning the category to Default save location removed the rule. The original disabled/empty rules configuration was restored after testing.
- Release-target instrumentation used the explicitly granted QA tree, an isolated database and staging directory. It verified MIME classification, destination capture, collision-safe reservations, real provider document creation, write/read byte equality, and duplicate-safe offline restore.
- A real HTTP transfer completed and published through SAF to the selected tree, with bytes checked against the deterministic fixture. A rule change during the transfer did not change that download's captured destination.
- A simulated revoked grant produced a default-folder reservation and an unavailable callback without losing a file. Actual provider read/write and persisted grants were exercised; real grant revocation was not performed on user folders.
- Unknown types and disabled rules fell back to the default allocator. Temporary documents, databases, staging files and settings changes were cleaned up.
- Device screenshots of overview/editor were inspected against the proposed controls; category rows are grouped and divided, folder choices use radios, and the selected-folder card includes Change folder.

## Coverage and scope decision — 2026-11-01

JVM tests cover MIME precedence, extension fallback, all categories, collisions, missing/revoked folders and captured destinations across rule edits. The SAF path supports volume-agnostic document-tree URIs. The connected Xiaomi has no mounted removable volume (rechecked with Android sm list-volumes all on 2026-11-01). The user explicitly excluded physical removable-SD support/qualification from scope. DMF-021 is therefore complete for category rules, collision preservation and captured destinations across active rule edits; physical SD is not represented as tested. Generic Android document-tree handling remains volume-agnostic, but this is not a physical-SD support claim.

The device harness accepts `-e qaTreeUri <explicitly-granted-isolated-tree-uri>` and skips without that argument. It also requires the deterministic localhost HTTP fixture at adb-reversed port 8941. It never selects or revokes a user's folder automatically.

Test harness: `app/src/androidTest/java/com/espitman/sdm/storage/CategoryFoldersDeviceTest.kt`. Latest combined runtime result: `OK (2 tests)` in `/tmp/sdm-archive-category-device-results.txt`.

## Closing focused regression — 2026-11-01

After the scope decision, reran release-unit suites FileCategoryTest (2), DestinationAllocatorTest (3) and SafDownloadDestinationPublisherTest (8): 13 tests, zero failures/errors/skips, BUILD SUCCESSFUL in 4 s. Evidence: docs/automatic-folders-validation/dmf021-release-regression-2026-11-01.log. These cover category/rule capture, reserved names, existing-file collision safety, publication and failure handling; prior real HTTP-to-SAF/rule-edit byte-equality evidence above remains valid. This closure changes documentation only; no app binary or storage behavior was changed.
