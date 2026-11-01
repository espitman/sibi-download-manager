# Native tutorial implementation — 2026-11-01

Current patch: **0.2.78** adds Skip / Next chapter to chapter selection, with Done only at final completion. [Validation](next-chapter/README.md). Patch **0.2.77** preserves the exact original splash and waits for its complete exit before opening the tutorial. [Regression proof](splash-fix/README.md). The 0.2.76 implementation results below remain dated evidence.

Design was approved by the user's explicit request to implement it. The design and DMF-021 scope closure were committed/pushed as `8e8e5b4` before implementation.

## Implementation

- `TourCatalog` provides 33 stable steps in six independent chapters. Chapters can be started individually and are marked complete only after their final step. Back, Skip, chapter selection and completion share one state machine.
- `TutorialStore` owns only `sdm_tutorial_v1`. New installs get an optional offer; dismissal is remembered. Interrupted running sessions offer Resume at the saved chapter/step; Settings includes a searchable Restart tutorial entry.
- A full-screen modal retains the real SdmApp composition underneath. Leaving the tour returns to the previous actual view, rather than navigating the real app through sample screens. Incoming real torrent/notification requests suspend the tutorial without dropping its saved running position.
- `TourSamples` is in-memory. Native AppHeader, BottomNavigation, DownloadCard, DetailsHero, NumberGrid, ToggleRow, TimeChoice and TimeWheel are reused with sample-only callbacks. There is no download repository, network client, WebView, real clipboard read/write, transfer service or file mutation in the sample scene.
- The only external handoff is the explicit Continue on phone folder-picker action. Selecting a folder changes feedback for this tour only; it does not persist a grant or update the real destination. Other preview/import/export/restore/retry/download operations demonstrate samples, never invoke those real operations.
- The target is measured from its rendered bounds. The shared coach goes above or below it; when neither side fits, a split layout keeps the target separate from a scrolling coach and reachable buttons. Keyboard, large font and missing-target fallback are handled. Native theme tokens, icons and fonts are shared; sample theme changes have their own palette and do not change preferences or the main activity window.

## Final validation

900 release-unit tests, zero failures/errors/skips; full lintRelease, release assembly and test APK assembly pass. Build: 0.2.76 / versionCode 78, non-debuggable release signed with the existing local QA certificate. Exact outputs/hashes: results.json, release-checks.log and device-tests.log.

On physical Xiaomi 2107113SG, serial 30fe7f97: native UI suite **OK (4 tests), 28.845 seconds** on the final installed package. All 33 steps are reachable; every target has positive visible bounds and never overlaps its coach. Sample link editing, identity review and browser revocation remain isolated. Activity recreation offers Resume at the saved step; Back/Skip and Settings Restart tutorial return to the real Settings view. Original records, actual settings/category rules and all persisted URI grants are exactly preserved. The harness restores its original tutorial preferences after every case.

Actual 2× font rendering, a 320-logical-pixel keyboard layout and sample light theme pass. Dialog density inheritance was corrected after screenshot inspection exposed a test override being lost at the window boundary. At large font scale the tutorial reserves the space for target and coach instead of showing clipped decorative bottom-navigation labels; the coach text and target can scroll independently, with actions reachable. The sample palette changes no real theme preference or main-window flags.

Eighteen actual connected-device captures are in device/. Reviewed welcome/chapter/coach/target/arrow/button geometry and native tokens against approved tutorial boards and the existing Open Design/native component reference. The native DownloadStatusCard/DownloadTabs are reused, including their real layout rather than an illustrative redraw; selected controls match the app. Existing DownloadCard, DetailsHero, number/time/retry controls, icons, fonts and theme palettes are shared. Sample labels, isolated data and the compact accessibility fallback are intentional tutorial states. No automated whole-image zero-pixel-difference score is claimed.

Earlier ActivityScenario launches did not reach the activity in this MIUI test setup; those attempts are not counted as passes. The final native harness starts the authorized app via shell and uses accessibility actions. Physical USB reconnection was needed mid-task. The other user's emulator was not used or stopped. Process recovery coverage is activity recreation plus committed tutorial preferences; no separate real OS-kill test is claimed.

The test screenshots contain isolated tutorial examples. Picker launch requires the explicit Continue on phone action and never persists a grant; code/source isolation is documented above. No real file, clipboard, browser history/bookmark, transfer, restore or scheduler operation is invoked by the practice callbacks.
