package com.espitman.sdm.domain

/** Bounded newline import; URL equality is exact so signed query strings stay intact. */
object LinkArchive {
    const val MAX_FILE_BYTES = 5 * 1024 * 1024
    const val MAX_LINKS = 5000
    data class Preview(val urls: List<String>, val duplicates: Int, val invalid: Int)
    fun preview(text: String, existingUrls: Set<String>): Preview {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "File is too large (maximum 5 MB)." }
        val seen = existingUrls.toMutableSet(); val urls = mutableListOf<String>()
        var duplicates = 0; var invalid = 0; var lines = 0
        text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            require(++lines <= MAX_LINKS) { "Too many links (maximum 5000)." }
            when (val result = DownloadUrl.validate(line)) {
                is DownloadUrlResult.Valid -> if (seen.add(result.url)) urls += result.url else duplicates++
                is DownloadUrlResult.Invalid -> invalid++
            }
        }
        return Preview(urls, duplicates, invalid)
    }
    fun export(downloads: List<Download>): String = downloads.map { it.url }
        .filter { DownloadUrl.validate(it) is DownloadUrlResult.Valid }.distinct().joinToString("\n", postfix = if (downloads.isEmpty()) "" else "\n")
}
