# Storage location (SDM-039)

Supported range: minSdk 26 through targetSdk 35. Destination selection UI and the active-folder setting are SDM-040.

## Decision

Use a hybrid model. Keep writing to the current permission-free app-specific external Downloads directory by default. Obtain extra folders only through Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE` / `OpenDocumentTree`) with persistable read and write URI grants. Do not declare `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, or broad `READ_MEDIA_*` permissions.

App-specific `Context.getExternalFilesDir(DIRECTORY_DOWNLOADS)` is writable on every supported API without a runtime storage prompt. If that directory cannot be created, fall back to internal `filesDir/Downloads`, matching existing transfer behavior. Public MediaStore Downloads is not the default: it does not grant arbitrary user folders and is a weaker fit than SAF for a later Save location picker.

User-selected folders must come from the system document tree picker. The picker intent requests persistable, prefix, read, and write grants. After `RESULT_OK`, take persistable read/write on the returned tree URI (`content://…/tree/…`). A document URI, `file://` path, or other scheme is not a folder grant.

## Boundaries

- Default destination for new downloads remains the app-specific Downloads directory.
- This task does not wire Save location, persist a chosen URI as the active setting, or recover from revoked or deleted trees.
- Open folder navigation and Files listing stay later stage-6 work.
