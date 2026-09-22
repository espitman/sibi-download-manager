package com.espitman.sdm.network

import java.util.Base64

/**
 * Obtains a SHA-256 reference checksum from HTTP metadata headers.
 *
 * Accepts a 64-character hexadecimal `X-Checksum-Sha256` value, or a `sha-256`
 * member from `Content-Digest` / `Digest` in conventional `alg=base64` or
 * structured `alg=:base64:` form. Successful values are normalized to lowercase
 * 64-hex. Malformed, wrong-length, and non-SHA-256 algorithms yield null.
 */
object ReferenceSha256Parser {
    const val HEX_LENGTH = 64
    private const val DIGEST_BYTES = 32

    fun isNormalized(value: String): Boolean {
        if (value.length != HEX_LENGTH) return false
        return value.all { ch -> ch in '0'..'9' || ch in 'a'..'f' }
    }

    fun fromHeaders(
        xChecksumSha256: String?,
        contentDigest: String?,
        digest: String?,
    ): String? =
        parseHexHeader(xChecksumSha256)
            ?: parseSha256Dictionary(contentDigest)
            ?: parseSha256Dictionary(digest)

    fun parseHexHeader(raw: String?): String? {
        val value = raw?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length != HEX_LENGTH) return null
        if (value.any { ch -> ch.digitToIntOrNull(16) == null }) return null
        return value.lowercase()
    }

    private fun parseSha256Dictionary(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        for (member in raw.split(',')) {
            val parsed = parseDictionaryMember(member) ?: continue
            if (!parsed.algorithm.equals("sha-256", ignoreCase = true)) continue
            val decoded = decodeBase64(parsed.encoded) ?: continue
            if (decoded.size != DIGEST_BYTES) continue
            return decoded.toHexLower()
        }
        return null
    }

    private fun parseDictionaryMember(raw: String): DictionaryMember? {
        val trimmed = raw.trim()
        val separator = trimmed.indexOf('=')
        if (separator <= 0) return null
        val algorithm = trimmed.substring(0, separator).trim()
        if (algorithm.isEmpty()) return null
        val rest = trimmed.substring(separator + 1).trim()
        if (rest.isEmpty()) return null
        val encoded = if (rest.startsWith(':')) {
            val closing = rest.indexOf(':', startIndex = 1)
            if (closing <= 1) return null
            rest.substring(1, closing)
        } else {
            rest.substringBefore(';').trim().removeSurrounding("\"")
        }
        if (encoded.isEmpty()) return null
        return DictionaryMember(algorithm, encoded)
    }

    private fun decodeBase64(value: String): ByteArray? {
        if (value.any { it.isWhitespace() }) return null
        val padded = when (value.length % 4) {
            0 -> value
            2 -> "$value=="
            3 -> "$value="
            else -> return null
        }
        return try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun ByteArray.toHexLower(): String {
        val out = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            out[index * 2] = HEX_DIGITS[value ushr 4]
            out[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(out)
    }

    private data class DictionaryMember(
        val algorithm: String,
        val encoded: String,
    )

    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )
}
