# Browser session downloads — 0.2.72

Verified on 2026-10-02, Xiaomi 11T Pro / Android 14, 1080 × 2400 at 440 dpi.

## Behavior

Browser download confirmation includes “Use this browser session” and, for normal tabs, “Keep session for pause and resume”. Retained grants are encrypted using AES-GCM and an Android Keystore key in app-private no-backup storage. They never enter download backup exports. Private/unretained sessions save only a sign-in-required marker without cookies or referring-page history. Grant diagnostics show the origin and privacy state without credentials or paths. Completed/deleted downloads remove their grants.

Cookies and Referer remain confined to the exact scheme, host and port across metadata requests and every transfer redirect. Authentication and proxy-authentication headers are stripped. HTTP Basic has been removed from the product plan.

401/403 and redirects from known binary downloads to HTML require browser sign-in. Partial bytes remain untouched, and these failures are excluded from automatic retry. Cards and details provide the sign-in action. After login, explicitly selecting the download opens the existing item’s review sheet. The existing identity checks preserve progress for the same object; an unverified replacement requires confirmation to restart. The original queue item, destination and ordering are retained.

## Verification

- 826 JVM tests: zero failures, errors or skips. New coverage checks redacted diagnostics, expired-session classification, preserving partial bytes on 401, stopping before network access when a private grant is unavailable, resuming with a renewed cookie and matching range, and preventing a redirected sign-in HTML page from becoming a completed binary file. Existing origin/redirect tests pass.
- 40 device tests pass: Keystore encryption/reopen, missing private/unretained credentials, corrupt-grant fail-closed behavior, backup exclusion, WebView download handoff/privacy/configuration/preferences, safe link replacement and 27 SQLite repository regressions.
- Visible flow on the device: staged an isolated failed 1 MiB download with 4 KiB of existing bytes; “Sign in again” opened Browser; login set a fresh cookie; selecting the file opened “Update browser session”; checking showed “Same file · Progress will be preserved”; “Replace & resume” completed the same record. Its 1 MiB payload matched the source byte for byte, and its context was removed. The fixture record and files were cleaned up.
- Release confirmation and private-session controls were inspected on the connected device. Private confirmation omits disk retention and explains that restarting requires sign-in. Shared browser chrome/sheet controls were compared against the Open Design HTML rendered at the matching 393 × 873 logical viewport / 2.75 scale. New session states extend the existing custom SDM controls. The previously documented Avenir Next font availability limitation remains; no claim of full pixel equality is made.
- `testDebugUnitTest`, `lintRelease`, `assembleRelease`, `assembleDebugAndroidTest`, and `git diff --check` pass. Local graph refreshed using `graphify update .`; Graphify reports partial extraction for three Kotlin files, so source/build results remain authoritative.

## Release

Version 0.2.72 / code 74. Locally signed APK: `/Users/espitman/Desktop/APKs/SDM-0.2.72-release.apk`. Updated the connected device without clearing app data. Public distribution/production signing is unchanged.

## Scope limits

This covers browser cookies and request context, not HTTP Basic forms or automatic media discovery. A site that replaces the file without a verifiable identity may require restarting. HTML-based login detection is limited to a redirect from a download with a known non-HTML MIME type; arbitrary site-specific authentication flows are handled by explicit browser navigation.
