# Release preparation

The current release candidate uses application ID `com.espitman.sdm`, `versionName=0.2.10`, and `versionCode=12`. The declared minimum is Android 8.0 (API 26), with target SDK 35. The release build is non-debuggable and currently keeps code shrinking off; it has no configured distribution signing identity.

## Build

With JDK 17 and Android SDK API 35:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease
```

Gradle writes `app/build/outputs/apk/release/app-release-unsigned.apk` and `app/build/outputs/bundle/release/app-release.aab`. An unsigned artifact is not a distributable update. Keep signing keys and passwords outside this repository. The `.gitignore` excludes local keystores and build output; check `git status` before committing.

For local QA on a clean emulator, align and sign the unsigned APK with a disposable QA key outside the repository, then verify it with the Android SDK's `apksigner`. A QA signature must never be used as the production identity. A production update needs the same signing identity as its previous public release; an APK with another key cannot upgrade it without uninstalling and losing local app data.

For distribution, provision a protected upload/signing key through the chosen store's standard signing flow, sign the APK or AAB according to that store's requirements, verify package name, version, certificate, and hashes, then perform a staged release. No public upload or store publication is part of this preparation.

The exact QA package commands, devices, checksums, and remaining validation limits are recorded in [release validation](release-validation.md).
