# Core architecture

## Scope and boundaries

Stage 1 replaces prototype persistence and data ownership. It does not start network transfers. The Compose layout, animations, and user-approved visual changes remain intact. No automatic media detection, YouTube integration, or Always keep active mode is introduced.

## Data flow

UI observes application-scoped repositories. User commands go to repositories or, once implemented in stage 2, the download coordinator. Database records are the durable source of truth; Compose state holds only presentation concerns such as an open menu, search query, or selected filter.

- **Domain:** immutable download record and explicit state transitions. No Android UI dependency.
- **Download repository:** observable records backed by platform SQLite. Reads/writes run on an IO dispatcher, writes are serialized, and state transitions are checked atomically. UI never writes database tables directly.
- **Settings repository:** one application-scoped observable owner of existing `sdm_settings` SharedPreferences. Existing keys and values are retained. Theme, Settings, and quick preferences read the same source. Storage changes require no destructive migration.
- **Application dependencies:** repositories use application context and survive Activity recreation. No Activity or WebView is retained by them.
- **UI:** renders real records or the reference's existing empty states. Unavailable transfer metrics are not replaced with invented measurements.

## Persistence and recovery

SQLite schema versions and explicit forward migrations preserve records. No destructive fallback is permitted. A fresh database starts empty. Process recreation reloads records; it does not silently start work or relabel an interrupted transfer as completed. Startup recovery and scheduling policy belong to stages 3–4.

Persist ID, original URL, filename, MIME type, destination, optional total bytes, downloaded bytes, state, error, priority, timestamps, and HTTP validators needed for later safe resume. Unknown size is null, distinct from an empty file. Validate nonnegative sizes and progress and reject invalid state changes. Completion requires a real transfer result in the future coordinator.

## Future transfer boundary

Stage 2 introduces a coordinator that creates records, streams HTTP responses to temporary files, publishes progress, and finalizes files after success. Stage 3 runs that coordinator inside a foreground service. Stages 4 and 7 add queue scheduling, resume, retry, concurrency, and segmented transfers.

All UI and notification commands will address the same record ID and coordinator. The database is not a scheduler, and selecting a setting does not claim its future engine behavior is already implemented.

## Storage and privacy

Database and preferences use private app storage. File destinations are represented as paths/URIs without assuming that `/Download/SDM` is already writable. Actual destination selection and permission persistence are stage 6. Do not log URL credentials or sensitive headers. Do not store browser credentials in the download model by default.

## Verification

Test state transitions and invariants independently of Compose. Exercise SQLite creation, migration, invalid transitions, observable updates, and reopening on Android. Verify preservation and synchronization of existing settings. Relaunch the app on the connected device and compare empty-state captures against reference styling. Keep domain/service implementation separate from pixel-level presentation changes.
