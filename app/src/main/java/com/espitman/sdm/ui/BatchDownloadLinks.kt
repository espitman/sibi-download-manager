package com.espitman.sdm.ui

import com.espitman.sdm.domain.DownloadUrl
import com.espitman.sdm.domain.DownloadUrlResult
import com.espitman.sdm.download.SubmissionResult

internal data class ParsedDownloadLinks(
    val urls: List<String>,
    val invalidLines: List<String>,
)

/** One pasted line is one download, including when the line is a Markdown link. */
internal fun parseDownloadLinks(raw: String): ParsedDownloadLinks {
    val urls = mutableListOf<String>()
    val invalid = mutableListOf<String>()
    raw.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
        val markdownTarget = if (line.startsWith('[') && line.endsWith(')')) {
            val divider = line.lastIndexOf("](")
            if (divider > 0) line.substring(divider + 2, line.length - 1) else line
        } else line
        val candidate = markdownTarget.removeSurrounding("<", ">")
            .replace("\\&", "&")
            .replace("\\_", "_")
        when (val result = DownloadUrl.validate(candidate)) {
            is DownloadUrlResult.Valid -> urls += result.url
            is DownloadUrlResult.Invalid -> invalid += line
        }
    }
    return ParsedDownloadLinks(urls, invalid)
}

internal data class BatchSubmissionOutcome(
    val added: Int,
    val failedUrls: List<String>,
    val firstFailure: String?,
)

internal suspend fun submitDownloadLinks(
    urls: List<String>,
    submit: suspend (String) -> SubmissionResult,
): BatchSubmissionOutcome {
    var added = 0
    val failed = mutableListOf<String>()
    var firstFailure: String? = null
    urls.forEach { url ->
        val result = try {
            submit(url)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            SubmissionResult.Failure("Failed to submit download")
        }
        when (result) {
            is SubmissionResult.Success -> added++
            is SubmissionResult.Failure -> {
                failed += url
                if (firstFailure == null) firstFailure = result.message
            }
        }
    }
    return BatchSubmissionOutcome(added, failed, firstFailure)
}
