package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.CompletedFileProbe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class FileTypeFilter(val label: String) {
    All("All"),
    Video("Video"),
    Audio("Audio"),
    Documents("Documents"),
    Apk("APK"),
    Archives("Archives"),
}

internal enum class FileSortOption(val toast: String) {
    NewestFirst("Sorted newest first"),
    OldestFirst("Sorted oldest first"),
}

internal data class FileRowModel(
    val id: String,
    val type: String,
    val name: String,
    val meta: String,
    val verified: Boolean,
    val category: FileTypeFilter?,
    val sizeBytes: Long,
    val completedAtEpochMillis: Long,
)

internal fun presentCompletedFiles(
    records: List<Download>,
    filter: FileTypeFilter,
    query: String,
    sort: FileSortOption,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    probe: CompletedFileProbe,
    verifiedIds: Set<String> = emptySet(),
): List<FileRowModel> {
    val rows = records.mapNotNull { download ->
        mapCompletedFile(download, nowEpochMillis, zoneId, probe, verifiedIds)
    }
    return filterAndSortFiles(rows, filter, query, sort)
}

internal fun mapCompletedFile(
    download: Download,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    probe: CompletedFileProbe,
    verifiedIds: Set<String> = emptySet(),
): FileRowModel? {
    if (download.state != DownloadState.COMPLETED) return null
    val destination = download.destinationPath
    if (destination.isNullOrBlank()) return null
    if (!probe.isReadableDocument(destination)) return null
    val completedAt = download.completedAtEpochMillis ?: download.updatedAtEpochMillis
    return FileRowModel(
        id = download.id,
        type = fileBadge(download.fileName),
        name = download.fileName,
        meta = formatFileMeta(download.downloadedBytes, completedAt, nowEpochMillis, zoneId),
        verified = download.id in verifiedIds,
        category = classifyCompletedFile(download.mimeType, download.fileName),
        sizeBytes = download.downloadedBytes,
        completedAtEpochMillis = completedAt,
    )
}

internal fun filterAndSortFiles(
    files: List<FileRowModel>,
    filter: FileTypeFilter,
    query: String,
    sort: FileSortOption,
): List<FileRowModel> {
    val needle = query.trim()
    val filtered = files.filter { row ->
        matchesFileFilter(row, filter) && row.name.contains(needle, ignoreCase = true)
    }
    return filtered.sortedWith(fileSortComparator(sort))
}

internal fun matchesFileFilter(row: FileRowModel, filter: FileTypeFilter): Boolean {
    if (filter == FileTypeFilter.All) return true
    return row.category == filter
}

internal fun fileSortComparator(sort: FileSortOption): Comparator<FileRowModel> {
    val completed = compareBy<FileRowModel> { it.completedAtEpochMillis }
    val tieBreak = compareBy<FileRowModel> { it.id }
    return when (sort) {
        FileSortOption.NewestFirst -> completed.reversed().then(tieBreak)
        FileSortOption.OldestFirst -> completed.then(tieBreak)
    }
}

internal fun fileBadge(fileName: String): String =
    fileName.substringAfterLast('.', "FILE").uppercase(Locale.US).take(5)

internal fun formatFileMeta(
    sizeBytes: Long,
    completedAtEpochMillis: Long,
    nowEpochMillis: Long,
    zoneId: ZoneId,
): String = "${formatBytes(sizeBytes)} · ${formatCompletedTimestamp(completedAtEpochMillis, nowEpochMillis, zoneId)}"

internal fun formatCompletedTimestamp(
    completedAtEpochMillis: Long,
    nowEpochMillis: Long,
    zoneId: ZoneId,
): String {
    val completed = Instant.ofEpochMilli(completedAtEpochMillis).atZone(zoneId)
    val completedDate = completed.toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
    val time = completed.format(COMPLETED_TIME)
    return when {
        completedDate == today -> "Today, $time"
        completedDate == today.minusDays(1) -> "Yesterday, $time"
        else -> completed.format(COMPLETED_DAY)
    }
}

internal fun classifyCompletedFile(mimeType: String?, fileName: String): FileTypeFilter? {
    val mime = normalizeMime(mimeType)
    if (mime != null && !isGenericBinaryMime(mime)) {
        return classifySpecificMime(mime)
    }
    return classifyExtension(fileName)
}

private fun classifySpecificMime(mime: String): FileTypeFilter? = when {
    mime == ANDROID_PACKAGE_MIME -> FileTypeFilter.Apk
    mime.startsWith("video/") -> FileTypeFilter.Video
    mime.startsWith("audio/") -> FileTypeFilter.Audio
    isArchiveMime(mime) -> FileTypeFilter.Archives
    isDocumentMime(mime) -> FileTypeFilter.Documents
    else -> null
}

private fun classifyExtension(fileName: String): FileTypeFilter? {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    if (extension.isEmpty() || extension == fileName.lowercase(Locale.US)) return null
    return when (extension) {
        in VIDEO_EXTENSIONS -> FileTypeFilter.Video
        in AUDIO_EXTENSIONS -> FileTypeFilter.Audio
        in APK_EXTENSIONS -> FileTypeFilter.Apk
        in ARCHIVE_EXTENSIONS -> FileTypeFilter.Archives
        in DOCUMENT_EXTENSIONS -> FileTypeFilter.Documents
        else -> null
    }
}

private fun normalizeMime(mimeType: String?): String? {
    val raw = mimeType?.trim()?.lowercase(Locale.US) ?: return null
    if (raw.isEmpty()) return null
    return raw.substringBefore(';').trim().takeIf { it.isNotEmpty() }
}

private fun isGenericBinaryMime(mime: String): Boolean = mime in GENERIC_BINARY_MIMES

private fun isArchiveMime(mime: String): Boolean = mime in ARCHIVE_MIMES

private fun isDocumentMime(mime: String): Boolean {
    if (mime.startsWith("text/")) return true
    if (mime in DOCUMENT_MIMES) return true
    if (mime.startsWith("application/vnd.openxmlformats-officedocument.")) return true
    if (mime.startsWith("application/vnd.oasis.opendocument.")) return true
    if (mime.startsWith("application/vnd.ms-")) return true
    return false
}

private val COMPLETED_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val COMPLETED_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)

private const val ANDROID_PACKAGE_MIME = "application/vnd.android.package-archive"

private val GENERIC_BINARY_MIMES = setOf(
    "application/octet-stream",
    "binary/octet-stream",
    "application/binary",
    "application/force-download",
    "application/x-download",
    "application/download",
    "application/unknown",
    "*/*",
)

private val ARCHIVE_MIMES = setOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/x-7z-compressed",
    "application/x-rar-compressed",
    "application/vnd.rar",
    "application/gzip",
    "application/x-gzip",
    "application/x-tar",
    "application/x-gtar",
    "application/x-bzip",
    "application/x-bzip2",
    "application/x-xz",
    "application/x-lzip",
    "application/x-compress",
    "application/vnd.ms-cab-compressed",
    "application/java-archive",
    "application/zstd",
    "application/x-zstd",
)

private val DOCUMENT_MIMES = setOf(
    "application/pdf",
    "application/rtf",
    "application/msword",
    "application/vnd.msword",
    "application/epub+zip",
    "application/x-mobipocket-ebook",
    "application/vnd.amazon.ebook",
    "application/json",
    "application/xml",
    "application/xhtml+xml",
    "application/javascript",
    "application/x-javascript",
    "message/rfc822",
)

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "m4v", "ts", "m2ts", "3gp", "mpeg", "mpg", "ogv", "vob",
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "aiff", "alac", "mid", "midi",
)

private val APK_EXTENSIONS = setOf("apk", "xapk", "apks")

private val ARCHIVE_EXTENSIONS = setOf(
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "tbz", "tbz2", "cab", "lz", "lzma", "zst",
)

private val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "txt", "md", "rtf", "doc", "docx", "odt", "xls", "xlsx", "ods", "csv", "ppt", "pptx",
    "odp", "epub", "html", "htm", "xml", "json", "tex", "log", "rst", "adoc",
)
