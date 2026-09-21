package com.espitman.sdm.download

/**
 * Strict HTTP Range resume helpers.
 *
 * Request:
 * - `Range: bytes=<offset>-`
 * - `If-Range` is the stored strong ETag when present, otherwise Last-Modified.
 *
 * Response:
 * - `Content-Range` must be `bytes <start>-<end>/<total>` (or `*` total when unknown).
 * - Start must equal the expected resume offset; total must match a known length.
 * - Stored validators are compared to response `ETag` / `Last-Modified`.
 */
object HttpRangeResume {

    const val HEADER_RANGE: String = "Range"
    const val HEADER_IF_RANGE: String = "If-Range"
    const val HEADER_CONTENT_RANGE: String = "Content-Range"
    const val HEADER_ETAG: String = "ETag"
    const val HEADER_LAST_MODIFIED: String = "Last-Modified"
    const val HTTP_PARTIAL_CONTENT: Int = 206
    const val HTTP_OK: Int = 200
    const val HTTP_RANGE_NOT_SATISFIABLE: Int = 416

    private val CONTENT_RANGE_REGEX =
        Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

    data class ResumeValidators(
        val etag: String? = null,
        val lastModified: String? = null,
    ) {
        fun strongEtag(): String? = etag?.takeIf { isStrongEtag(it) }
    }

    data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?,
    ) {
        init {
            require(start >= 0L) { "Content-Range start must be non-negative" }
            require(end >= start) { "Content-Range end must be >= start" }
            total?.let {
                require(it >= 0L) { "Content-Range total must be non-negative" }
                require(end < it) { "Content-Range end must be < total" }
            }
        }

        val inclusiveLength: Long get() = end - start + 1L
    }

    sealed class ResumeValidation {
        data object Ok : ResumeValidation()
        data class Failed(val reason: String) : ResumeValidation()
    }

    enum class ResumeStatusAction {
        ContinuePartial,
        UseFullBodyRestart,
        FetchFreshGet,
        Fail,
    }

    fun classifyResumeStatus(statusCode: Int): ResumeStatusAction = when (statusCode) {
        HTTP_PARTIAL_CONTENT -> ResumeStatusAction.ContinuePartial
        HTTP_OK -> ResumeStatusAction.UseFullBodyRestart
        HTTP_RANGE_NOT_SATISFIABLE -> ResumeStatusAction.FetchFreshGet
        else -> ResumeStatusAction.Fail
    }

    fun rangeHeaderValue(offset: Long): String {
        require(offset >= 0L) { "Range offset must be non-negative" }
        return "bytes=$offset-"
    }

    fun ifRangeHeaderValue(stored: ResumeValidators): String? {
        stored.strongEtag()?.let { return it.trim() }
        stored.lastModified?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    fun requestHeaders(offset: Long, stored: ResumeValidators): Map<String, String> {
        val headers = linkedMapOf(HEADER_RANGE to rangeHeaderValue(offset))
        ifRangeHeaderValue(stored)?.let { headers[HEADER_IF_RANGE] = it }
        return headers
    }

    fun parseContentRange(header: String?): ContentRange {
        val value = header?.trim().orEmpty()
        require(value.isNotEmpty()) { "Content-Range header is required" }
        val match = CONTENT_RANGE_REGEX.matchEntire(value)
            ?: throw IllegalArgumentException("Invalid Content-Range: $value")
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val totalToken = match.groupValues[3]
        val total = if (totalToken == "*") null else totalToken.toLong()
        return ContentRange(start = start, end = end, total = total)
    }

    fun validateContentRange(
        header: String?,
        expectedOffset: Long,
        knownTotal: Long? = null,
    ): ContentRange {
        require(expectedOffset >= 0L) { "Expected resume offset must be non-negative" }
        knownTotal?.let { require(it >= 0L) { "Known total must be non-negative" } }
        val parsed = parseContentRange(header)
        require(parsed.start == expectedOffset) {
            "Content-Range start ${parsed.start} does not match expected offset $expectedOffset"
        }
        if (knownTotal != null) {
            require(parsed.total != null) {
                "Content-Range total is unknown but known total is $knownTotal"
            }
            require(parsed.total == knownTotal) {
                "Content-Range total ${parsed.total} does not match known total $knownTotal"
            }
        }
        return parsed
    }

    fun validateStoredValidators(
        stored: ResumeValidators,
        responseEtag: String?,
        responseLastModified: String?,
    ): ResumeValidation {
        val storedStrong = stored.strongEtag()?.let(::normalizeEtag)
        val responseNormalized = responseEtag?.let(::normalizeEtag)
        if (storedStrong != null) {
            if (responseNormalized == null) {
                return ResumeValidation.Failed("Response ETag is missing; stored strong ETag required")
            }
            if (!isStrongEtag(responseEtag.trim())) {
                return ResumeValidation.Failed("Response ETag is not a strong validator")
            }
            if (storedStrong != responseNormalized) {
                return ResumeValidation.Failed("Response ETag does not match stored strong ETag")
            }
            return ResumeValidation.Ok
        }
        val storedLm = stored.lastModified?.trim()?.takeIf { it.isNotEmpty() }
        if (storedLm != null) {
            val responseLm = responseLastModified?.trim()?.takeIf { it.isNotEmpty() }
                ?: return ResumeValidation.Failed("Response Last-Modified is missing; stored Last-Modified required")
            if (storedLm != responseLm) {
                return ResumeValidation.Failed("Response Last-Modified does not match stored Last-Modified")
            }
            return ResumeValidation.Ok
        }
        return ResumeValidation.Failed("Resume requires a stored strong ETag or Last-Modified")
    }

    fun validateDeclaredBodyLength(inclusiveLength: Long, contentLength: Long): Unit {
        require(inclusiveLength > 0L) { "Content-Range length must be greater than 0" }
        if (contentLength < 0L) return
        require(contentLength == inclusiveLength) {
            "Response Content-Length $contentLength does not match Content-Range length $inclusiveLength"
        }
    }

    fun validateReceivedBodyLength(inclusiveLength: Long, receivedBytes: Long): Unit {
        require(inclusiveLength > 0L) { "Content-Range length must be greater than 0" }
        require(receivedBytes >= 0L) { "Received body length cannot be negative" }
        require(receivedBytes == inclusiveLength) {
            "Response body length $receivedBytes does not match Content-Range length $inclusiveLength"
        }
    }

    fun isStrongEtag(etag: String): Boolean {
        val value = etag.trim()
        if (value.isEmpty()) return false
        if (value.startsWith("W/", ignoreCase = true)) return false
        return value.startsWith("\"") && value.endsWith("\"") && value.length >= 2
    }

    private fun normalizeEtag(etag: String): String = etag.trim()
}
