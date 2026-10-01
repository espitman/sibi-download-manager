# Link import/export and backup verification

Release 0.2.69 (versionCode 71), Xiaomi 2107113SG / Android 14, USB serial 30fe7f97, 2026-10-01.

## Physical-device checks

- Import selected a real TXT document through Android DocumentsUI. Four lines produced two new links, one duplicate and one invalid line. Add to queue created two paused records and dismissed the sheet without freezing or changing button dimensions.
- Export All wrote a real TXT file containing exactly 19 distinct HTTP/HTTPS URL lines. No extra metadata, headers or cookies were present.
- Export Selected wrote a separate TXT document containing exactly the single selected QA URL. Share exposed a readable FileProvider TXT attachment through the system chooser; the chooser was canceled without sending anything.
- Backup wrote a valid version-1 JSON document with 19 download records, settings and category-folder rules. Record fields exclude local paths, progress, IDs and private request context.
- Restore rejected malformed JSON with a visible error. A one-record backup restored a fresh paused record at zero progress; selecting it again displayed one duplicate and restoring it again added nothing.
- Release-target instrumentation passed real SQLite insertion, duplicate URL exclusion, transaction rollback, database reopening and Android JSON round-trip checks.
- Import, Export, Backup and Restore use the common SDM sheet shell and animation. Device screenshots were inspected after restoring the framed file picker, divided file list, connected segments, icon-led summary, grouped backup options and outlined information block.
- Three temporary records were removed through selection and Delete. Downloads returned to the original counts: All 17, Queue 10, Completed 7. Existing downloads were not resumed or edited.

## Automated checks

809 JVM tests passed; release assembly and lint passed. Import tests cover signed URLs, CRLF/BOM, duplicate lines, invalid URLs, bounded 5 MiB / 5,000-link input, partial metadata failures and clean reservation rollback. Codec tests cover unsupported versions, malformed/deep JSON, numeric range violations, settings-only/list-only backups, private-field exclusions and compatibility without category-folder rules.

Test harness: `app/src/androidTest/java/com/espitman/sdm/data/LinkArchiveDeviceTest.kt`. Runtime report retained locally at `/tmp/sdm-archive-category-device-results.txt`.
