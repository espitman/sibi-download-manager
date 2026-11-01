# Chapter continuation — 2026-11-01

Choose a chapter now offers Skip and a primary Next chapter. Next chapter starts step 1 of the next unfinished chapter in order, wrapping to earlier unfinished chapters when necessary. Explicit chapter selection remains available. It does not mark skipped/unvisited chapters complete. Done is shown only on the final completed-tour screen.

Installed non-debuggable release 0.2.78 / versionCode 80. Release-unit suite, lint and package assembly pass (counts in results.json). Physical phone suite: OK (5 tests), 35.888 seconds. The full 33-step test now proceeds through every chapter using Next chapter, asserts no intermediate Done, verifies Skip is present, and uses Done only after all six chapters are complete. Separate Skip/restart test exits from Choose a chapter. Original records/settings/grants are preserved. Splash, actual large-font, keyboard and sample-theme regressions remain included. No emulator was used or stopped.

Actual phone screenshots: choose-chapter.png and completed.png.
