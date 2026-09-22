# SDM 0.2.0 delivery

The verified release-candidate source was committed as `8636764` (`feat: prepare SDM 0.2.0 release candidate`) and pushed to `main` at `https://github.com/espitman/sibi-download-manager`. This report and the final checklist tick are the follow-up documentation commit.

Tasks SDM-064 through SDM-069 are complete with evidence in [release validation](release-validation.md), [security review](sdm-065-security-review.md), and [visual QA](sdm-066-visual-qa.md). The release candidate has 654 passing JVM tests, 61 selected API 36 instrumentation tests, 28 selected API 34 instrumentation tests, passing debug lint, debug/release APK and AAB builds, and an API 36 release-package HTTP download with matching SHA-256. The latest `BrowserWebViewConfigTest` additionally passed on both devices after checking file/content access flags.

On the Xiaomi Android 14 phone, the debug build `0.2.0` was installed with `adb install -r` without clearing data. Its existing downloads and settings remained available. The phone was not rebooted or rotated. The isolated API 36 emulator verified a fresh install, v1→v2 upgrade preserving data, and a QA-signed non-debuggable release package preserving two Completed records.

The unsigned release APK and AAB are build outputs, not committed files. Their SHA-256 values and signing requirements are in [release preparation](release.md) and [release validation](release-validation.md). No signing key, QA APK, store listing, or publicly downloadable release package was published. Production signing and public publication require a distribution identity and a separate publication request.

Known unmeasured areas are the API 26 runtime, unplugged battery drain, actual physical storage exhaustion, external SAF grant revocation, live untrusted TLS certificate, and a physical reboot. The display text cannot exactly use proprietary Avenir Next without redistribution rights; the body font IBM Plex Sans is embedded under OFL. These limits are tracked explicitly in the validation and visual reports.
