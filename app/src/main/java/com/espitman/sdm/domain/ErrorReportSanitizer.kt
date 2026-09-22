package com.espitman.sdm.domain

/**
 * Strips URLs, file paths, credentials, and secret-bearing tokens from text
 * that is persisted or shown as a download error. Classification keywords
 * (HTTP status, timeout, ENOSPC, TLS, connect failures) are preserved.
 */
object ErrorReportSanitizer {
    const val MAX_LENGTH = 96

    private val SCHEME_URL = Regex(
        """(?i)\b(?:https?|ftp|file|content|android|javascript|data):[^\s<>"']+""",
    )
    private val PROTOCOL_RELATIVE_URL = Regex("""(?<![A-Za-z0-9_])//[^\s<>"']+""")
    private val WINDOWS_PATH = Regex("""(?i)\b[A-Z]:\\[^\s<>"']+""")
    private val UNIX_PATH = Regex(
        """(?<![A-Za-z0-9_])/(?:data|storage|sdcard|mnt|tmp|proc|system|vendor|apex|dev|etc|Users|private|var|home|opt|usr|android)[^\s<>"']*""",
    )
    private val ABSOLUTE_PATH = Regex("""(?<![A-Za-z0-9:])/(?:[\w.+$@~-]+/){1,}[\w.+$@~-]*""")
    private val AUTH_HEADER = Regex(
        """(?i)\b(?:cookie|set-cookie|authorization|proxy-authorization|x-api-key)\s*[:=]\s*.+?(?=\s+HTTP\s+\d{3}\b|$)""",
    )
    private val QUERY_SECRET = Regex(
        """(?i)(?:\?|&)?(?:token|access_token|auth|signature|sig|key|password|passwd|secret|credential|session)[=:][^\s&]+""",
    )
    private val USERINFO = Regex("""[A-Za-z0-9._%+-]+:[^@\s/]+@""")
    private val CONNECT_TARGET = Regex(
        """(?i)(\b(?:connect(?:ing)? to|host)\s+)(?:"[^"]+"|'[^']+'|\[[^\]]+\](?::\d+)?|[^\s,;]+)""",
    )
    private val WHITESPACE = Regex("""\s+""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,;:.])""")
    private val TRAILING_SEPARATORS = Regex("""(?:\s*[:;,-])+$""")

    fun sanitize(raw: String?, maxLength: Int = MAX_LENGTH): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw.replace(WHITESPACE, " ").trim()
        text = AUTH_HEADER.replace(text, "")
        text = SCHEME_URL.replace(text, "")
        text = PROTOCOL_RELATIVE_URL.replace(text, "")
        text = QUERY_SECRET.replace(text, "")
        text = USERINFO.replace(text, "")
        text = WINDOWS_PATH.replace(text, "")
        text = UNIX_PATH.replace(text, "")
        text = ABSOLUTE_PATH.replace(text, "")
        text = CONNECT_TARGET.replace(text, "$1")
        text = text.replace(WHITESPACE, " ").trim()
        text = SPACE_BEFORE_PUNCT.replace(text, "$1")
        text = TRAILING_SEPARATORS.replace(text.trim(), "")
        text = text.trim()
        if (text.isEmpty()) return ""
        if (text.length <= maxLength) return text
        val keep = (maxLength - 1).coerceAtLeast(0)
        return text.take(keep).trimEnd() + "…"
    }
}
