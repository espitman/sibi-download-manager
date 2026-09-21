package com.espitman.sdm.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object DownloadFilenameResolver {

    const val DEFAULT_FALLBACK_FILENAME = "downloadfile"
    const val MAX_FILENAME_BYTES = 255

    // Reserved Windows / Android FAT / NTFS device names
    private val RESERVED_NAMES = hashSetOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    // Platform-hostile characters: / \ : * ? " < > | and control characters (0x00..0x1F, 0x7F)
    private val ILLEGAL_CHARS_REGEX = Regex("[\u0000-\u001F\u007F/\\\\:*?\"<>|]")

    /**
     * Resolves the filename following the precedence:
     * 1. Content-Disposition: filename* (RFC 5987 / RFC 6266)
     * 2. Content-Disposition: filename
     * 3. Final redirected URL path segment
     * 4. Safe fallback ("downloadfile")
     */
    fun resolveFilename(
        contentDisposition: String?,
        url: String?,
        fallback: String = DEFAULT_FALLBACK_FILENAME
    ): String {
        val parsedFromDisposition = parseContentDisposition(contentDisposition)
        if (parsedFromDisposition != null) {
            val sanitized = sanitize(parsedFromDisposition)
            if (sanitized != null) {
                return sanitized
            }
        }

        val parsedFromUrl = parseUrlPath(url)
        if (parsedFromUrl != null) {
            val sanitized = sanitize(parsedFromUrl)
            if (sanitized != null) {
                return sanitized
            }
        }

        return sanitize(fallback) ?: DEFAULT_FALLBACK_FILENAME
    }

    /**
     * Resolves collisions deterministically against an existence predicate.
     * If the filename doesn't exist, it is returned unchanged.
     * If it exists, returns "name (1).ext", "name (2).ext", etc.
     */
    fun resolveCollision(
        baseFilename: String,
        existsPredicate: (String) -> Boolean
    ): String {
        return resolveReservation(baseFilename) { candidate ->
            !existsPredicate(candidate)
        }
    }

    /**
     * Atomically reserves a filename using candidate reservation callback.
     * Calls reserveAction(candidate) which returns true only when reservation / CREATE_NEW succeeds.
     * Tries candidate, then "name (1).ext", "name (2).ext", etc. within MAX_FILENAME_BYTES (UTF-8).
     */
    fun resolveReservation(
        baseFilename: String,
        reserveAction: (String) -> Boolean
    ): String {
        val sanitized = sanitize(baseFilename) ?: DEFAULT_FALLBACK_FILENAME
        if (reserveAction(sanitized)) {
            return sanitized
        }

        val lastDotIndex = sanitized.lastIndexOf('.')
        val (stem, extension) = if (lastDotIndex > 0 && lastDotIndex < sanitized.length - 1) {
            Pair(sanitized.substring(0, lastDotIndex), sanitized.substring(lastDotIndex))
        } else {
            Pair(sanitized, "")
        }

        var counter = 1
        while (true) {
            val suffix = " ($counter)"
            val candidate = buildFilenameWithCap(stem, suffix, extension, MAX_FILENAME_BYTES)
            if (reserveAction(candidate)) {
                return candidate
            }
            counter++
        }
    }

    /**
     * Parses Content-Disposition header with RFC 5987 / 6266 precedence:
     * First looks for filename*, then filename.
     * If filename* has invalid encoding, it is ignored so fallback to filename or URL can occur.
     * Tolerates malformed headers.
     */
    fun parseContentDisposition(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null

        try {
            val parts = splitHeaderParameters(contentDisposition)

            var starFilename: String? = null
            var normalFilename: String? = null

            for (part in parts) {
                val eqIdx = part.indexOf('=')
                if (eqIdx == -1) continue

                val paramName = part.substring(0, eqIdx).trim()
                val paramVal = part.substring(eqIdx + 1).trim()

                if (paramName.equals("filename*", ignoreCase = true)) {
                    val decoded = parseRfc5987Value(paramVal)
                    if (!decoded.isNullOrBlank() && starFilename == null) {
                        starFilename = decoded
                    }
                } else if (paramName.equals("filename", ignoreCase = true)) {
                    val unquoted = cleanParameterValue(paramVal)
                    if (!unquoted.isNullOrBlank() && normalFilename == null) {
                        normalFilename = unquoted
                    }
                }
            }

            return starFilename ?: normalFilename
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Parses RFC 5987 encoded value format: charset'lang'encoded-value
     * RFC 5987 uses strict percent-encoding where '+' is a literal plus, not a space.
     * If the charset is unsupported or the percent encoding is invalid, returns null.
     */
    private fun parseRfc5987Value(raw: String): String? {
        val clean = cleanQuotes(raw)
        val firstQuote = clean.indexOf('\'')
        if (firstQuote == -1) return null

        val charsetName = clean.substring(0, firstQuote).trim()
        val secondQuote = clean.indexOf('\'', firstQuote + 1)
        if (secondQuote == -1) return null

        val encodedValue = clean.substring(secondQuote + 1)

        val charset = try {
            if (charsetName.isNotBlank()) Charset.forName(charsetName) else StandardCharsets.UTF_8
        } catch (_: Exception) {
            return null
        }

        return rfc5987PercentDecode(encodedValue, charset)
    }

    /**
     * Strict RFC 5987 percent-decoding:
     * - '+' is left untouched (NOT decoded to space).
     * - '%HH' is decoded as raw byte hex.
     * - Invalid '%' sequence returns null so caller can fall back to normal filename.
     */
    private fun rfc5987PercentDecode(input: String, charset: Charset): String? {
        val bos = ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%') {
                if (i + 2 >= input.length) {
                    return null
                }
                val hex1 = Character.digit(input[i + 1], 16)
                val hex2 = Character.digit(input[i + 2], 16)
                if (hex1 == -1 || hex2 == -1) {
                    return null
                }
                bos.write((hex1 shl 4) + hex2)
                i += 3
            } else {
                // Non-ASCII characters shouldn't strictly appear in attr-char, but encode as UTF-8 bytes if present
                val charBytes = c.toString().toByteArray(StandardCharsets.UTF_8)
                bos.write(charBytes, 0, charBytes.size)
                i++
            }
        }

        return try {
            val bytes = bos.toByteArray()
            val decoder = charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanParameterValue(raw: String): String? {
        var value = cleanQuotes(raw)
        // Check if value is path (e.g. C:\path\file.ext or /path/file.ext) - extract basename
        value = value.replace('\\', '/')
        if (value.contains('/')) {
            value = value.substringAfterLast('/')
        }
        return value.ifBlank { null }
    }

    private fun cleanQuotes(raw: String): String {
        var str = raw.trim()
        if (str.startsWith('"')) {
            if (str.endsWith('"') && str.length >= 2) {
                str = str.substring(1, str.length - 1)
            } else {
                str = str.substring(1)
            }
            str = str.replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return str
    }

    private fun splitHeaderParameters(header: String): List<String> {
        val result = mutableListOf<String>()
        var inQuotes = false
        var isEscaped = false
        val current = StringBuilder()

        for (ch in header) {
            if (isEscaped) {
                current.append(ch)
                isEscaped = false
                continue
            }
            if (ch == '\\') {
                current.append(ch)
                isEscaped = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (ch == ';' && !inQuotes) {
                result.add(current.toString().trim())
                current.setLength(0)
                continue
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }
        return result
    }

    /**
     * Extracts and decodes the final path segment from the URL string using HttpUrl / URI.
     * Guarantees:
     * - Host is never returned as a filename
     * - Query parameters (?) and fragment (#) are excluded
     * - Returns null if there are no non-empty path segments
     * - URL-decodes the path segment (+ remains + in path segments)
     */
    fun parseUrlPath(url: String?): String? {
        if (url.isNullOrBlank()) return null

        try {
            // Try parsing with OkHttp HttpUrl
            val httpUrl = url.toHttpUrlOrNull()
            if (httpUrl != null) {
                val segments = httpUrl.pathSegments
                val lastSegment = segments.lastOrNull { it.isNotBlank() }
                return lastSegment?.ifBlank { null }
            }

            // Fallback for non-HTTP URIs (e.g. file:, ftp:)
            val uri = URI.create(url)
            val path = uri.path ?: return null
            val segments = path.split('/').filter { it.isNotBlank() }
            val lastSegment = segments.lastOrNull() ?: return null
            return decodeUrlPathSegment(lastSegment).ifBlank { null }
        } catch (_: Exception) {
            // For malformed URLs that fail standard parsers, attempt safe path extraction without treating host as file
            return fallbackParseUrlPath(url)
        }
    }

    private fun fallbackParseUrlPath(rawUrl: String): String? {
        try {
            var s = rawUrl.trim()
            val hashIdx = s.indexOf('#')
            if (hashIdx != -1) s = s.substring(0, hashIdx)
            val qIdx = s.indexOf('?')
            if (qIdx != -1) s = s.substring(0, qIdx)

            // Strip scheme://host
            val schemeEnd = s.indexOf("://")
            val pathPart = if (schemeEnd != -1) {
                val slashAfterHost = s.indexOf('/', schemeEnd + 3)
                if (slashAfterHost == -1) return null
                s.substring(slashAfterHost)
            } else {
                s
            }

            val segments = pathPart.split('/').filter { it.isNotBlank() }
            val lastSegment = segments.lastOrNull() ?: return null
            return decodeUrlPathSegment(lastSegment).ifBlank { null }
        } catch (_: Exception) {
            return null
        }
    }

    private fun decodeUrlPathSegment(segment: String): String {
        return try {
            val bos = ByteArrayOutputStream(segment.length)
            var i = 0
            while (i < segment.length) {
                val c = segment[i]
                if (c == '%' && i + 2 < segment.length) {
                    val h1 = Character.digit(segment[i + 1], 16)
                    val h2 = Character.digit(segment[i + 2], 16)
                    if (h1 != -1 && h2 != -1) {
                        bos.write((h1 shl 4) + h2)
                        i += 3
                        continue
                    }
                }
                val b = c.toString().toByteArray(StandardCharsets.UTF_8)
                bos.write(b, 0, b.size)
                i++
            }
            bos.toString(StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            segment
        }
    }

    /**
     * Sanitizes candidate filename:
     * - Prevents path traversal (strips slash, backslash, ..)
     * - Replaces illegal chars (/ \ : * ? " < > | controls) with '_'
     * - Handles dot / dot-dot ("." or "..")
     * - Strips leading/trailing unsafe dots and whitespace
     * - Prefixes '_' for Windows/FAT reserved names (CON, PRN, etc.)
     * - Preserves Unicode
     * - Caps UTF-8 byte length to MAX_FILENAME_BYTES (255 bytes) preserving extension and Unicode code points
     */
    fun sanitize(rawFilename: String?): String? {
        if (rawFilename.isNullOrBlank()) return null

        var name = rawFilename.replace('\\', '/')
        if (name.contains('/')) {
            name = name.substringAfterLast('/')
        }

        name = ILLEGAL_CHARS_REGEX.replace(name, "_")
        name = name.trim('.', ' ')

        if (name.isEmpty() || name == "." || name == "..") {
            return null
        }

        val dotIndex = name.indexOf('.')
        val stem = (if (dotIndex != -1) name.substring(0, dotIndex) else name).trim()
        if (RESERVED_NAMES.contains(stem.uppercase())) {
            name = "_$name"
        }

        if (name.toByteArray(StandardCharsets.UTF_8).size > MAX_FILENAME_BYTES) {
            val lastDot = name.lastIndexOf('.')
            name = if (lastDot > 0 && lastDot < name.length - 1) {
                val base = name.substring(0, lastDot)
                val ext = name.substring(lastDot)
                buildFilenameWithCap(base, "", ext, MAX_FILENAME_BYTES)
            } else {
                truncateUtf8CodePoints(name, MAX_FILENAME_BYTES).trimEnd('.', ' ')
            }
        }

        name = name.trim('.', ' ')
        return name.ifBlank { null }
    }

    /**
     * Truncates a string to fit within maxBytes when encoded as UTF-8,
     * without splitting Unicode code points (surrogate pairs or multi-byte sequences).
     */
    fun truncateUtf8CodePoints(str: String, maxBytes: Int): String {
        if (str.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) {
            return str
        }
        val sb = java.lang.StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < str.length) {
            val codePoint = str.codePointAt(i)
            val charCount = java.lang.Character.charCount(codePoint)
            val cpString = str.substring(i, i + charCount)
            val cpBytes = cpString.toByteArray(StandardCharsets.UTF_8).size
            if (currentBytes + cpBytes > maxBytes) {
                break
            }
            sb.append(cpString)
            currentBytes += cpBytes
            i += charCount
        }
        return sb.toString()
    }

    /**
     * Builds a filename: truncatedBase + middle + extension such that the total UTF-8 byte size <= maxBytes.
     */
    private fun buildFilenameWithCap(
        base: String,
        middle: String,
        extension: String,
        maxBytes: Int
    ): String {
        val middleAndExt = middle + extension
        val middleAndExtBytes = middleAndExt.toByteArray(StandardCharsets.UTF_8).size

        if (middleAndExtBytes >= maxBytes) {
            // Even middle + ext exceeds maxBytes; truncate the whole combination
            val raw = base + middle + extension
            return truncateUtf8CodePoints(raw, maxBytes).trimEnd('.', ' ')
        }

        val allowedBaseBytes = maxBytes - middleAndExtBytes
        val truncatedBase = truncateUtf8CodePoints(base, allowedBaseBytes).trimEnd('.', ' ')
        return truncatedBase + middleAndExt
    }
}
