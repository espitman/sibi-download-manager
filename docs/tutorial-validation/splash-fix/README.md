# Original splash preserved — 2026-11-01

The tutorial's separate Android Dialog was composed while the existing launch overlay was still animating. A separate window is drawn above a Compose overlay, so it covered the original splash.

SdmLaunchLayer now publishes readiness from the real AnimatedVisibility transition. SdmApp opens the tutorial only after the splash animation and its existing 260 ms exit fade are fully idle. Main app content continues composing underneath as before. No artificial delay was added, and SplashLaunchPolicy still shows the animation only for a fresh app session.

Compared with pre-tutorial commit 8e8e5b4, the complete private SdmSplashScreen renderer is byte-identical: drawing, colors, geometry, wordmark handling, animation curves/durations and delays. Original wordmark resources and launch policy are unchanged. Hash: results.json.

Installed non-debuggable release 0.2.77 / versionCode 79, existing local QA signing. 901 release-unit tests pass, lintRelease and assembly pass. Physical-phone suite: OK (5 tests), 35.150 s, including the four tutorial/data-preservation regressions plus a new test that checks no tutorial dialog at 400 ms and waits at least 1650 ms for the original animation and exit (phone animator scale 1.0). A focused settled-window capture rerun passes (6.330 s); the 350 ms pause is in the screenshot harness only, not production code.

Actual phone captures: original-splash.png and after-splash.png. Renderer hash equivalence verifies original source preservation; no computed screenshot-zero-difference score is claimed. No emulator was used or stopped.
