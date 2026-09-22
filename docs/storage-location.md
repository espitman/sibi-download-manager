# Storage location (SDM-039, SDM-040)

Supported range: minSdk 26 through targetSdk 35.

## Decision

Use a hybrid model. Keep writing to the current permission-free app-specific external Downloads directory by default. Obtain extra folders only through Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE` / `OpenDocumentTree`) with persistable read and write URI grants. Do not declare `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, or broad `READ_MEDIA_*` permissions.

App-specific `Context.getExternalFilesDir(DIRECTORY_DOWNLOADS)` is writable on every supported API without a runtime storage prompt. If that directory cannot be created, fall back to internal `filesDir/Downloads`, matching existing transfer behavior. Public MediaStore Downloads is not the default: it does not grant arbitrary user folders and is a weaker fit than SAF for a Save location picker.

User-selected folders must come from the system document tree picker. The picker intent requests persistable, prefix, read, and write grants. After `RESULT_OK`, take persistable read/write on the returned tree URI (`content://…/tree/…`). A document URI, `file://` path, or other scheme is not a folder grant.

## Active save location (SDM-040)

Settings and Quick Preferences launch the system folder picker. The chosen tree URI and a stable user-facing folder label are stored in `sdm_settings` (`save_location_tree_uri`, `save_location_label`) and restored after process death. The default label remains `/Download/SDM`.

New downloads capture the active destination at submission:

- App-specific: stream and finalize in the app Downloads directory, as before.
- User tree: stream to a local `.part` / staging file so pause, resume, retry, collision handling, and checksums keep working, then publish the completed file into the granted tree. The record stores `destination_tree_uri` and `destination_display_label` through additive database v8.

If the persisted tree is deleted, the provider is unavailable, or the grant is revoked, SDM clears the setting, falls back to the app-specific Downloads directory, and shows a toast. In-flight tree downloads that cannot be published keep the completed local file in app-specific Downloads instead of crashing.

Open folder navigation and Files listing stay later stage-6 work.
