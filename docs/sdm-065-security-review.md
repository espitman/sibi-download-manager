# SDM-065 security review

Review of URL and filename handling, Android permissions, credential storage, and logs. Sensitive values are stripped from error reports before they are persisted or shown.

## Defects found and fixed

1. **Error reports included source URLs and redirect locations.** Metadata failures embedded the current URL, `Location` header, and resolved redirect URL. Those strings can carry query tokens. OkHttp connect and host-resolution messages also named hosts and addresses. The same text was stored on the failed record and shown on details as Last error.
2. **Error reports included filesystem paths.** Destination-exists, missing-part, and directory-creation failures interpolated absolute paths. Reservation failures from the destination allocator did the same. Add showed those messages inline; a failed transfer persisted them.
3. **Exception and header leftovers could carry credentials.** Cookie, Authorization, and `user:password@host` fragments that appeared in an exception or HTTP reason phrase were stored as-is. Display sanitization only collapsed whitespace and truncated to 96 characters.
4. **Private Browser WebView left file access at platform defaults.** On API 26–29, `allowFileAccess` defaults to true. File and content access, including file-URL bridging, are now explicitly off.
5. **Cloud and device-transfer backup could copy the download database.** `sdm-downloads.db` stores source URLs that may include query tokens. Auto Backup and device-transfer rules now exclude that database and its journal/WAL files.
6. **Filenames could carry bidi and zero-width marks.** Content-Disposition or URL path segments could keep U+202E and similar marks, which spoof extensions. Those marks are now stripped with the other illegal filename characters.

## Evidence

| Area | Finding | Fix | JVM evidence |
| --- | --- | --- | --- |
| Error reports | Persisted/shown failures included URLs, tokens, hosts, IPs, and paths | `ErrorReportSanitizer` at persist and display; metadata and finalization messages no longer interpolate URLs or paths | `ErrorReportSanitizerTest`, `DownloadFailurePresentationTest.lastErrorOmitsUrlsTokensAndFilesystemPaths`, `DownloadDetailsTelemetryTest.lastErrorAppearsOnlyForFailedRecordsAndStaysDistinctPerCategory`, `HttpDownloadMetadataRetrieverTest.redirectToUnsupportedSchemeIsRejected`, `DownloadTransferEngineTest` destination-exists and blocked-parent cases, `DownloadSubmissionCoordinatorTest` directory failure |
| URL intake | HTTP/HTTPS only; userinfo and fragments rejected | Existing `DownloadUrl` checks plus `data:` and `content:` rejection | `DownloadUrlTest` |
| Filenames | Traversal, reserved names, and controls already sanitized | Also strip bidi overrides and zero-width marks | `DownloadFilenameResolverTest.sanitizesHostileCharactersAndControls` |
| Permissions | Manifest already omits broad storage and network-mutation permissions | Allowed-permission set is now explicit and checked against the packaged manifest | `NetworkPermissionPolicyTest`, `AppSecurityManifestTest.manifestPermissionsStayMinimalAndComponentsStayPrivate` |
| FileProvider | Non-exported provider, grant-on-use | Paths stay `Download/` and `Downloads/`; test rejects `.` and `/` | `AppSecurityManifestTest.backupRulesExcludeTheDownloadDatabase` |
| Credentials | Browser cookies stay in memory and are same-origin only | Unchanged. Download model still does not store Cookie or Authorization | Existing `ScopedRequestContext` tests; this review |
| Logs | No `Log`, `println`, `printStackTrace`, or `HttpLoggingInterceptor` in production sources | Regression scan of `app/src/main/java` | `AppSecurityManifestTest.productionSourcesDoNotCallLogOrPrintSensitiveTraces` |
| Backup | Download database was included in Auto Backup | `sdm_backup_rules.xml` and `sdm_data_extraction_rules.xml` exclude `sdm-downloads.db` | `AppSecurityManifestTest.backupRulesExcludeTheDownloadDatabase` |
| WebView | File access not pinned off | `BrowserWebViewSecurityPolicy` disables file/content access and file-URL bridging | `BrowserWebViewSecurityPolicyTest` |

Classification of failures still uses the same HTTP, timeout, storage, TLS, and connect phrases after sanitization. Cards still show only the classified label. Details Last error shows the label plus the sanitized detail.

## Residual limitations

- Source URLs remain in the download database because transfers and resume need them. Query tokens in a user-supplied URL are therefore still on device. They are no longer written into the `error` column or backup archives.
- Settings and the save-location tree URI can still be included in Auto Backup. Those values are not credentials; persistable tree grants do not restore on another device.
- `usesCleartextTraffic="true"` remains so HTTP downloads and the Browser can load HTTP pages.
- The private Browser still enables JavaScript and DOM storage so ordinary pages work. File and content access are off. A page that depends on `file://` or `content://` rendering will not load those resources.
- User-chosen download URLs may target loopback, link-local, or RFC1918 hosts. That is required for local test servers and is not blocked.
- Browser cookies can remain in `CookieManager` if the process dies before the private session is cleared. They are not stored on the download record. A protected download may need to be selected again after process death.
- Copy URL still copies the stored source URL. That is a user action, not an error report.
- Details still show the URL host and HTTP/HTTPS label. The path, query, and userinfo are not shown there.
- No crash reporter or third-party log pipeline is packaged. A future reporter must use `ErrorReportSanitizer` before upload.
- The initial Grok code pass did not use ADB. Final `BrowserWebViewConfigTest` instrumentation checked all four disabled file/content access flags on both Android 14 and Android 16; see `docs/release-validation.md`.

No Always-keep-active, automatic media detection, or YouTube-specific paths were added.
