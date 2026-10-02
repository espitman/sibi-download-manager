# Torrent and Magnet downloads

SDM embeds FrostWire jlibtorrent `2.0.12.9` and libtorrent `2.0.12`. There is no companion app, remote download service, or user-installed engine. Native libraries for ARMv7, ARM64, x86 and x86_64 ship in the APK. The restricted FrostWire Maven repository supplies only the `com.frostwire` group.

## Adding and controlling a torrent

Add accepts magnet links and HTTP/HTTPS `.torrent` URLs. The Torrent file tab opens Android's document picker. Browser magnet navigation and explicitly selected torrent downloads use the same sheet. Android VIEW intents support magnet links and torrent documents/URLs.

Resolve metadata first, choose contained files, choose a save location, and use Download or Add to queue. The existing scheduler controls simultaneous transfers, Wi-Fi policy, scheduling, pause/resume and cancellation. The card distinguishes torrents and shows peers, seeds, checking and seeding. Torrent info lists selected files, upload speed, uploaded bytes, ratio and the seeding switch. Completed selected files open with their appropriate MIME type.

Seeding is off by default. When enabled, the job stays active after the selected files finish until paused or seeding is turned off. BitTorrent can upload verified pieces while downloading even with post-download seeding off. Missing peers or missing pieces can leave a torrent waiting; private trackers may not serve magnet metadata, in which case use the `.torrent` file.

HTTP browser-session credentials apply only to retrieving the `.torrent` file, within their existing origin scope. They are not passed to the native engine, peers or trackers.

## Persistence and storage

Metadata, selected indices, seeding preference and native resume data are stored per job. The native engine checks existing pieces after abrupt process death. Queue recovery retains the record and verified progress. Portable backups include metadata and selection; restoring reserves a fresh folder and starts paused at zero, without claiming that downloaded payload was backed up. Existing URLs win conflicts.

Each job receives a fresh owned directory. Paths are constrained to that directory; absolute paths, symlinks, unsafe segments and duplicate file paths are rejected. Cancellation preserves partial data for resuming; deleting an unfinished job removes its owned data. Removing a completed record retains local payload unless the user requests file deletion.

Android document providers cannot serve as native random-access storage. SDM downloads into app storage, then copies only selected verified files into a newly created provider folder. Publication records successful files so a retry avoids duplicate copies. During publication both copies consume space; afterward the private working copy is removed. Provider access loss or a write failure leaves the job available for retry. An unfinished job's partially published folder is removed only through its stored ownership reference when deletion is requested.

Limits: metadata is bounded to 8 MiB and 5,000 files. A portable backup remains subject to the existing overall backup-size limit. Native per-job and session limits share the global bandwidth budget with HTTP transfers. Android's foreground-service and background-execution policies continue to apply.

## Licenses

Redistributed notices are packaged under `assets/licenses`: [jlibtorrent MIT](../app/src/main/assets/licenses/jlibtorrent_mit.txt), [libtorrent BSD](../app/src/main/assets/licenses/libtorrent_bsd.txt), [Boost Software License 1.0](../app/src/main/assets/licenses/boost_1_0.txt), and [OpenSSL Apache 2.0](../app/src/main/assets/licenses/openssl_apache_2_0.txt).

Primary upstream references: [FrostWire release](https://github.com/frostwire/frostwire-jlibtorrent/releases/tag/release%2F2.0.12.9), [libtorrent API](https://libtorrent.org/reference-Core.html), and [native build versions](https://github.com/frostwire/frostwire-jlibtorrent/blob/release/2.0.12.9/swig/build-utils.shinc).
