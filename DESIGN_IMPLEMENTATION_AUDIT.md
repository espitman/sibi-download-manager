# SDM Android design implementation audit

Reviewed source: Open Design project `13ab3c0f-1414-409f-934f-981d4a24c89f`

Status legend:

- **Ready**: can be implemented directly with normal Android APIs.
- **Conditional**: implementable, but needs a custom subsystem, an OS-version fallback, or a narrower product promise.
- **Revise**: the current wording or behavior cannot be guaranteed reliably on modern Android.

## Global shell and visual system

| Item | Status | Implementation note |
|---|---|---|
| Black & Gold and Light & Gold themes | Ready | Jetpack Compose `MaterialTheme` plus app-owned color tokens. Persist with DataStore. |
| Shared header across Downloads, Browser, Files, Settings | Ready | One reusable Compose top bar. |
| Fixed floating bottom navigation | Ready | `Scaffold` overlay with system-bar insets. It must use real device insets rather than the prototype's fixed 412×915 frame. |
| Large center Add action | Ready | Custom center navigation item. |
| Bottom-sheet slide and scrim animations | Ready | Material 3 `ModalBottomSheet`; use `imePadding()` for the URL keyboard. |
| Dark translucent/blurred navigation surface | Conditional | Blur quality varies by Android version and device. Use a translucent fallback where runtime blur is unavailable or too expensive. |
| English-only UI | Ready | Keep all strings in `strings.xml` even if only English ships initially. |
| Responsive layout | Conditional | The prototype is designed at 412×915. Small phones, tablets, landscape, display zoom, and large fonts still need responsive rules. |
| Accessibility | Conditional | Most touch targets are near 44–48 px, but several 9–10 px labels are too small. Compose semantics, TalkBack labels, font scaling, focus order, and contrast need an explicit pass. |

## Downloads home

| Item | Status | Implementation note |
|---|---|---|
| Header, search, and overflow menu | Ready | Search can filter the Room-backed download list. |
| Aggregate speed, downloaded today, remaining bytes, connection count | Ready | Values should come from the download engine and database instead of UI state. |
| Active-download indicator | Ready | Derived from transfer states. |
| Downloading / Queued / Completed filters | Ready | Room query or filtered `StateFlow`. |
| Download cards, progress, speed, ETA, pause/resume | Ready | Requires a persistent transfer state model. |
| Start queued item | Ready | Queue manager changes state and schedules the transfer. |
| Download All / Pause All | Ready | Batch commands against the queue. |
| Empty state | Ready | Normal conditional Compose content. |
| Keep active sheet | Conditional | A partial wake lock can be held only while useful work runs. Foreground/background execution must follow modern Android job and service rules. |
| Keep active while downloading / until queue completes | Ready | Revised design now limits the wake-lock promise to active or queued transfers and releases it automatically afterward. |
| Global speed limit and presets | Ready | Straightforward only with an app-owned network/download engine; Android's system DownloadManager does not expose this control. |
| Wi-Fi-only speed-limit rule | Ready | Observe network capabilities and apply the limiter conditionally. |
| Quick preferences synchronized with Settings | Ready | Use one DataStore repository as the source of truth. |

## Add download sheet

| Item | Status | Implementation note |
|---|---|---|
| Multiline URL input | Ready | Compose text field with URL keyboard options and bounded height. |
| Paste and Clear | Ready | Clipboard API; handle an empty or non-URL clipboard. |
| File name, type, and size preview | Conditional | Probe with HEAD or a small ranged GET and parse `Content-Disposition`, MIME type, and length. Many servers omit or misreport these fields, so unknown states are required. |
| Queue | Ready | Persist the job before scheduling it. |
| Download immediately | Ready | Start a user-visible transfer job/service and show progress notification. |
| Long URLs and signed URLs | Ready | Store securely in Room; redact sensitive query data from ordinary logs. |

## Download details

| Item | Status | Implementation note |
|---|---|---|
| Progress ring, metrics, speed history chart | Ready | Stream samples from the engine; cap the in-memory history window. |
| Pause / Resume | Ready | Persist byte ranges and validators so resume survives process death. |
| Cancel while keeping partial data | Ready | Define a separate Remove action later if deleting the partial file is desired. |
| Priority | Ready | Queue ordering plus scheduler priority. |
| Copy URL | Ready | Clipboard API. |
| Rename | Conditional | Easy for app-owned files; shared files must be changed through MediaStore or the persisted SAF grant. |
| Verify checksum | Ready | SHA-256 or user-supplied hash in background work; the current design must define which checksum is expected. |
| Move to top | Ready | Queue position update. |
| Source host, save location, request headers | Ready | Store metadata with the transfer. Sensitive headers such as cookies or authorization must be masked in the UI and logs. |
| TLS version | Ready | Available from the HTTP client's completed handshake, not before connecting. |
| Resume support | Conditional | Confirm through `Accept-Ranges`, validators, and an actual range response; headers alone are not always trustworthy. |
| Segment breakdown and connection count | Ready | Requires a custom segmented downloader and a server that supports byte ranges. Fall back to one stream when ranges are unsupported. |
| Open folder | Conditional | Launch the system document UI or a persisted SAF directory URI. Arbitrary raw filesystem navigation is not reliable under scoped storage. |

## Embedded browser

| Item | Status | Implementation note |
|---|---|---|
| Web page, address/search field, back, forward, reload, home, paste | Ready | Android WebView plus a URL/search parser. |
| Chrome-like header and menu | Ready | Compose controls around WebView. |
| Multiple tabs and tab switcher | Ready | One WebView state per tab; suspend or destroy background WebViews to control memory. |
| New tab and close tab | Ready | Normal tab-store operations. |
| Private tab / private by default | Conditional | AndroidX WebKit multi-profile can isolate cookies and storage only when the installed WebView supports `MULTI_PROFILE`. Older providers need a documented fallback; simply clearing cookies is not equivalent to private isolation. |
| History | Ready | App-owned history database; private tabs must never write to it. |
| Find in page | Ready | WebView find APIs. Remove the desktop-style `⌘F` hint from the Android UI. |
| Desktop site | Ready | Toggle user agent and viewport behavior, then reload. Some sites may still ignore it. |
| Search engine choice | Ready | Store engine template in DataStore. |
| Clear browsing data | Conditional | Reliable per-profile clearing depends on WebView multi-profile support; otherwise clearing may affect all normal tabs. |
| Tracker blocking | Conditional | Possible with maintained filter lists and request interception, but it will not match a full browser engine in every redirect, service-worker, or encrypted request path. Define the supported rule set. |

## Files

| Item | Status | Implementation note |
|---|---|---|
| Device storage usage card | Ready | Use storage statistics for the selected volume. Clarify whether “used” means whole device or SDM files. |
| File type filters | Ready | Query app records/MIME types. |
| Search and newest/oldest sort | Ready | Room query and indexed filename column. |
| Recent downloaded files | Ready | Source from the app database and reconcile with storage changes. |
| Complete / Verified badges | Ready | “Verified” must mean a real checksum/signature result. |
| File selection | Ready | Compose selection state. |
| Per-file overflow actions | Ready | The prototype currently only shows a toast; define Open, Share, Rename, Delete, and Details actions before implementation. |
| Listing every file in public Downloads | Conditional | App-created downloads are straightforward. Files created by other apps require MediaStore/SAF access and cannot be assumed visible by raw path. |
| Open APK | Conditional | Can launch the package installer through a content URI, but installation requires user action and the “install unknown apps” permission flow. |

## Settings

| Item | Status | Implementation note |
|---|---|---|
| Connections per download (8/16/24/32) | Ready | Engine setting; cap or reduce automatically when a server/device cannot sustain it. |
| Simultaneous downloads (1–10) | Ready | Queue scheduler limit. |
| Auto-resume | Ready | Persist state and reschedule recoverable transfers. |
| Wi-Fi only | Ready | Network constraints and live pause/resume behavior. |
| Save location | Conditional | Use MediaStore for public Downloads or `ACTION_OPEN_DOCUMENT_TREE` for a user-selected directory. Do not model the location as an unrestricted raw path. |
| Completion notifications | Ready | Requires notification channels and Android 13+ notification permission for drawer visibility. |
| Speed alerts | Ready | Define the stall threshold, duration, and cooldown. |
| Theme selector | Ready | DataStore-backed app theme. |
| Language: English only / Fixed | Ready | Display-only row. |
| Version | Ready | Read from package metadata rather than hardcode `4.8.2`. |
| Reset settings | Ready | Reset DataStore preferences without deleting downloads or files, matching the confirmation copy. |
| Settings search | Ready | The prototype currently only emits a toast; actual search/filter behavior still needs to be designed. |

## Dialogs and state handling

| Item | Status | Implementation note |
|---|---|---|
| Cancel-download confirmation | Ready | Preserve partial data and validators. |
| Reset-settings confirmation | Ready | Separate preferences from download records/files. |
| Toast/snackbar feedback | Ready | Prefer Snackbar for actions with possible failure. |
| Process death and app restart | Conditional | Every queue, progress checkpoint, tab metadata, and preference that should survive must live outside composable state. |

## Design inconsistencies to fix before implementation

1. The home card says **4 active**, but the visible list contains two active downloads and one queued download. The aggregate speed `18.6 MB/s` also equals the two visible active speeds (`12.4 + 6.2`), so the active count should currently be **2**.
2. The displayed **3.25 GB remaining** does not match the visible cards. The incomplete portions plus the queued 4.83 GB total substantially more; decide whether “Remaining” excludes queued items and label it accordingly.
3. The Add Download preview knows the exact filename, type, and 2.18 GB size before any visible analysis/loading state. The production UI needs Loading, Unknown, Error, authentication-required, and redirect states.
4. “Premium status” and “SDM Premium” appear in the UI, but there is no account, entitlement, purchase, restore, or feature-gating flow. Either define the premium model or remove that product claim from version one.
5. Browser History, Settings search, Save location, file overflow actions, Rename, Verify checksum, and Move to top are toast-only placeholders in the prototype. Their destination sheets/dialogs and failure states still need final UI definitions.
6. The design uses several 9–10 px labels and a fixed phone canvas. Typography and layout must be validated at Android font scales up to at least 200% and on narrow screens.

## Recommended implementation boundary

The complete visual design is implementable in Kotlin and Jetpack Compose. The core product should use an app-owned segmented download engine (for pause/resume, connection count, speed limiting, headers, and segment progress), Room for transfer/file metadata, DataStore for settings, MediaStore/SAF for storage, WebView plus AndroidX WebKit for browsing, and Android's user-visible background transfer APIs.

Do not base the product on Android's system `DownloadManager` if the design must retain per-download connections, global bandwidth limits, segment progress, custom headers, priority, and detailed resume behavior.

The design now avoids indefinite background-service and universal media-detection promises. The remaining browser constraint is private-profile behavior on WebView providers that do not support multi-profile isolation.
