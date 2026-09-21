# SDM-001 — Implementation audit

Source baseline: `0c0facb`. Scope: all Kotlin application sources, manifest, resources inventory, and app dependencies. This is a static code audit, not a new device or visual test. No application behavior was changed.

## Summary

The app is a Compose UI prototype with a working WebView and persisted settings. There is no download engine, download database, file repository, transfer service, queue scheduler, or notification implementation. No unit or instrumentation test sources are present under `app/src`.

Status terms: **sample** = hardcoded data; **UI-only** = changes local presentation; **no-op** = no action; **persisted-only** = saved but not applied to transfers; **working** = connected to an actual implementation, without implying production readiness.

## Downloads

Source: [DownloadsScreen.kt](../app/src/main/java/com/espitman/sdm/ui/DownloadsScreen.kt), `InteractiveDownloadsScreen`, `DownloadStatusCard`, and `DownloadCard`.

| Item | Current implementation | Follow-up |
| --- | --- | --- |
| Download records | Sample Dune MKV, SDM APK, and Editorial ZIP records; names, sizes, progress, rates, remaining times, and categories are initialized in code. | SDM-003–007 |
| Persistence | `rememberSaveable` plus a custom `DownloadsSaver`; supports saved UI state, not durable download storage. `SdmApp` retains Downloads state across tab navigation. | SDM-005 |
| Dashboard | Fixed 2 active, 18.6 MB/s, 8.42 GB downloaded today, 725 MB remaining, and 16 connections. These do not follow pause/start actions. | SDM-032 |
| Search | Working filtering of sample filenames; no real repository. | SDM-031 |
| Category tabs | Filter sample records only after a tab is selected; initial view deliberately includes all three sample records. No real completion transition exists. | SDM-015, 031 |
| Download All | Changes category/paused state and changes Queued to Connecting; displays a toast. No transfer begins. | SDM-028 |
| Pause All | Sets local `paused` flags and shows a toast. | SDM-028 |
| Card Start / Pause / Resume | Changes local flags/category and text only; progress remains fixed. | SDM-023–025 |
| Card details navigation | Only the Dune card opens details; details always receives `downloads.first()`. Other cards have no open handler. | SDM-033 |
| Menus, sheets, search field | Open/close interactions work. Opening a panel does not mean its underlying download feature exists. | See settings inventory below |

## Download details

Source: [DownloadsScreen.kt](../app/src/main/java/com/espitman/sdm/ui/DownloadsScreen.kt), `DownloadDetailsScreen`, `DetailsHero`, `MetricsGrid`, `SpeedChart`, `TechnicalInfo`, and `DisclosureInfo`.

| Item | Current implementation | Follow-up |
| --- | --- | --- |
| Progress and metrics | Fixed 72% ring, 12.4 MB/s, 1.57/2.18 GB, 01:04 remaining, and 16 connections. Only ACTIVE/PAUSED responds to the local flag. | SDM-013, 034 |
| Speed history | Fixed chart points and “Last 60 sec · 12.4 MB/s”; no sampling. | SDM-034 |
| Technical information | Fixed source host, `/Download/SDM`, TLS 1.3, resume availability, and 16 parallel threads. None comes from an HTTP response. | SDM-034 |
| Request headers / segments | Disclosures open correctly, but headers and four segment progress groups are sample values. User-Agent says SDM/4.8 while the app version is 0.1.0. | SDM-034, 050–051 |
| Pause / Resume | Toggles the same sample record's flag. | SDM-023–025 |
| Priority | Toggles a local highlight and toast, with no queue effect. | SDM-027 |
| Copy URL | Really writes to the clipboard, but copies a hardcoded sample URL rather than a record URL. | SDM-035 |
| Rename / Verify checksum / Move to top | Each closes the menu and shows a success-like toast; no rename, checksum work, or reordering occurs. | SDM-035–036 |
| Open folder | Toast only; does not launch a folder viewer. | SDM-039–042 |
| Cancel confirmation | Sets `item.state` to Canceled and returns. Does not change category or paused flag, remove a record, or handle a partial file. The “Partial file kept” message is unsupported. | SDM-026 |

## Add download

Source: [AddDownloadSheet.kt](../app/src/main/java/com/espitman/sdm/ui/AddDownloadSheet.kt), `AddDownloadSheet`; caller: `SdmApp`.

| Item | Current implementation | Follow-up |
| --- | --- | --- |
| URL input / Paste / Clear | Working text entry and clipboard operations. Input lives only in the open sheet. | SDM-008 |
| Filename / type preview | Derived from URL text by string splitting; no response metadata, MIME lookup, sanitization, or collision handling. | SDM-009–010 |
| Queue | Only runs animated dismissal; accepts even an empty input. No record is created. | SDM-027 |
| Download | Enabled for any nonblank text; only dismisses the sheet. No URL callback, validation, HTTP request, or file write. | SDM-008, 011–014 |
| Close / outside / Back | Connected to animated dismissal. | Retain during integration |

## Settings and quick controls

Sources: [SettingsScreen.kt](../app/src/main/java/com/espitman/sdm/ui/SettingsScreen.kt), [DownloadsScreen.kt](../app/src/main/java/com/espitman/sdm/ui/DownloadsScreen.kt), and [Theme.kt](../app/src/main/java/com/espitman/sdm/ui/theme/Theme.kt).

| Setting | Storage / current effect | Follow-up |
| --- | --- | --- |
| Connections | `sdm_settings.connections`, default 16; persisted-only. Quick Preferences uses the same key. | SDM-006, 051–052 |
| Simultaneous downloads | `simultaneous`, default 3; persisted-only, no scheduler. | SDM-027, 052 |
| Auto-resume | `auto_resume`, default true; persisted-only. | SDM-048 |
| Wi-Fi only | `wifi_only`, default true; persisted-only, no network enforcement. | SDM-046 |
| Download complete notifications | `download_complete`, default true; persisted-only, no notification producer. | SDM-018–019, 049 |
| Speed alerts | `speed_alerts`, default false; persisted-only, no stall detector. | SDM-049 |
| Save location | Fixed `/Download/SDM` in both settings surfaces; click only shows “Save location editor opened”. | SDM-039–040 |
| Theme | `theme`, default dark; working. A preference listener changes the app palette. | Preserve |
| Language | Explicitly fixed to English; informational, not a missing language picker. | No extra feature implied |
| Version | Reads the installed package version; working informational value. | Preserve |
| Reset | Really resets the settings state and clears the shared preferences, restoring dark theme. Does not reset separate Keep active / Speed limit UI state or an engine. | SDM-053 |
| Keep SDM awake / duration | `rememberSaveable` values, default enabled and “while downloading”; no durable setting, wake lock, or service. Save shows a toast. | SDM-006, 020 |
| Speed limit / Unlimited / Wi-Fi-only limit | `rememberSaveable` values, default 10 MB/s, limited, and not Wi-Fi-only; UI controls work but no throttling exists. Apply shows a toast. | SDM-006, 047 |
| Quick Preferences Done / Open all settings | Writes the four shared keys; navigation works. Changes are saved only on these actions. | SDM-006, 052 |

Additional state issues: Keep active and Speed limit edit their parent state immediately, so Cancel does not roll changes back. Settings and Quick Preferences read stored values when their local state is created; they do not share an observable repository. A restored Settings state can therefore be stale after changes elsewhere. Theme is the exception because it has a preference listener.

## Files

Source: [FilesScreen.kt](../app/src/main/java/com/espitman/sdm/ui/FilesScreen.kt), `files`, `FilesScreen`, `StorageCard`, and `FileRow`.

| Item | Current implementation | Follow-up |
| --- | --- | --- |
| File list | Five fixed MKV/APK/ZIP/FLAC/PDF entries with sample names, sizes, dates, and Complete/Verified labels. No file-system query or integrity check. | SDM-007, 041 |
| Storage summary | Fixed 82.4 GB of 128 GB and a 64% bar. | SDM-044 |
| Category filters | Selection styling changes, but the list always renders all five records. | SDM-041 |
| Search / sort / more in header | Shared `HeaderAction` has an empty click handler. | SDM-041 |
| File row | No open action. | SDM-042 |
| File actions menu | Empty click handler; no share, rename, delete, or menu implementation. | SDM-042–043 |

## Browser

Source: [BrowserScreen.kt](../app/src/main/java/com/espitman/sdm/ui/BrowserScreen.kt), `BrowserScreen`, `normalizeUrl`, and `BrowserLanding`.

| Item | Current implementation | Follow-up |
| --- | --- | --- |
| Address / search / quick access | Connected to a real WebView; searches use a Google URL, shortcuts load Vimeo, Archive, and SoundCloud. These are configured links, not sample downloads. | SDM-055 |
| Back / reload | Call actual WebView APIs; Back returns to the landing page when no WebView history remains. | SDM-055 |
| Tab counter | Fixed “1” drawn in a Box; no click handler or tab model. | SDM-056 |
| Browser menu | Empty click handler. | SDM-055–056 |
| Download integration | No download listener or callback to Add; no cookie/header handoff. | SDM-057–058 |
| Private label | UI claim only; no explicit private-session cleanup or isolation of cookies, cache, history, or DOM storage. DOM storage is enabled. | SDM-059 |
| Navigation state / errors | Bare WebViewClient; no page-state/error callbacks or explicit WebView cleanup. Address/currentUrl are local remembered state. `update` reloads currentUrl when it differs from WebView.url, so redirects/navigation can be reset on a later recomposition. | SDM-055, 059 |

## Shared UI, unused code, and infrastructure

- [SdmApp.kt](../app/src/main/java/com/espitman/sdm/ui/SdmApp.kt): bottom navigation, Add presentation, transitions, and toast presentation are wired. Settings header Search is also a no-op through shared `HeaderAction`; its intended action must be reconciled with the reference before implementation.
- The private legacy `DownloadsScreen`, `DownloadUi`, `sampleDownloads`, `StatusCard`, and their helpers remain in `SdmApp.kt` but are not used by the active destination router. Their empty bulk/card handlers are dead prototype code, not additional active controls. Shared `AppHeader` and navigation remain active and must not be removed with them. Cleanup belongs with SDM-007.
- [SdmSplashScreen.kt](../app/src/main/java/com/espitman/sdm/ui/SdmSplashScreen.kt): splash timing and lower bar are a decorative animation, not measured download or initialization progress.
- [AndroidManifest.xml](../app/src/main/AndroidManifest.xml): INTERNET and POST_NOTIFICATIONS are declared; only MainActivity is registered. No transfer service, receiver, or file-sharing provider exists. No foreground-service or wake-lock permission is declared.
- [MainActivity.kt](../app/src/main/java/com/espitman/sdm/MainActivity.kt): sets up theme, splash, and Compose. It does not request notification permission, create channels, or start transfer work.
- [app/build.gradle.kts](../app/build.gradle.kts): min SDK 26, target/compile SDK 35, version 0.1.0. Dependencies cover core/lifecycle/activity and Compose. The source tree has no download client, database implementation, scheduler, storage layer, or test sources. Standard platform networking remains available; dependency absence alone is not the evidence for missing transfers.
- No automatic media detector, automatic media downloader, YouTube-specific implementation, or Always keep active mode was found. Preserve those exclusions.

## Verification and handoff

Reviewed the active destination router and every screen's input/click callbacks, local state, settings reads/writes, file and network integration points, manifest components, dependencies, and complete `app/src` file inventory. Cross-checked sample constants and empty handlers with repository searches. Distinguished working UI actions and persisted preferences from actual transfer/file operations, and active code from unused legacy code.

SDM-001 is complete as an inventory task. Its completion does not mean the listed features have been implemented. Next task: SDM-002, architecture and integration decisions. No build, installation, or screenshot comparison was necessary for this documentation-only change.
