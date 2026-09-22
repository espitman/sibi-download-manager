package com.espitman.sdm.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceSha256ParserTest {
    @Test
    fun normalizesUppercaseHexChecksumHeader() {
        assertEquals(
            EMPTY_SHA256_HEX,
            ReferenceSha256Parser.parseHexHeader("  \"${EMPTY_SHA256_HEX.uppercase()}\"  "),
        )
        assertTrue(ReferenceSha256Parser.isNormalized(EMPTY_SHA256_HEX))
        assertFalse(ReferenceSha256Parser.isNormalized(EMPTY_SHA256_HEX.uppercase()))
    }

    @Test
    fun rejectsMalformedWrongLengthAndNonHexChecksumHeaders() {
        assertNull(ReferenceSha256Parser.parseHexHeader(null))
        assertNull(ReferenceSha256Parser.parseHexHeader("   "))
        assertNull(ReferenceSha256Parser.parseHexHeader(EMPTY_SHA256_HEX.dropLast(1)))
        assertNull(ReferenceSha256Parser.parseHexHeader(EMPTY_SHA256_HEX + "aa"))
        assertNull(ReferenceSha256Parser.parseHexHeader("g".repeat(64)))
        assertNull(ReferenceSha256Parser.parseHexHeader("sha-256:$EMPTY_SHA256_HEX"))
    }

    @Test
    fun parsesSha256FromConventionalDigestAndStructuredContentDigest() {
        assertEquals(
            EMPTY_SHA256_HEX,
            ReferenceSha256Parser.fromHeaders(
                xChecksumSha256 = null,
                contentDigest = null,
                digest = "SHA-256=$EMPTY_SHA256_BASE64",
            ),
        )
        assertEquals(
            EMPTY_SHA256_HEX,
            ReferenceSha256Parser.fromHeaders(
                xChecksumSha256 = null,
                contentDigest = "sha-256=:$EMPTY_SHA256_BASE64:",
                digest = null,
            ),
        )
        assertEquals(
            ZERO_SHA256_HEX,
            ReferenceSha256Parser.fromHeaders(
                xChecksumSha256 = null,
                contentDigest = "md5=:k7iFrfYGEF2HxHvURM+lgA==:, sha-256=:$ZERO_SHA256_BASE64:;q=1",
                digest = "sha-1=2jmj7l5rSw0yVb/vlWAYkK/YBwk=",
            ),
        )
    }

    @Test
    fun prefersValidXChecksumOverDigestEntries() {
        assertEquals(
            EMPTY_SHA256_HEX,
            ReferenceSha256Parser.fromHeaders(
                xChecksumSha256 = EMPTY_SHA256_HEX,
                contentDigest = "sha-256=:$ZERO_SHA256_BASE64:",
                digest = "sha-256=$ZERO_SHA256_BASE64",
            ),
        )
    }

    @Test
    fun rejectsOtherAlgorithmsMalformedBase64AndWrongDigestLength() {
        assertNull(
            ReferenceSha256Parser.fromHeaders(
                xChecksumSha256 = null,
                contentDigest = "sha-512=:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=:",
                digest = "md5=1B2M2Y8AsgTpgAmY7PhCfg==",
            ),
        )
        assertNull(
            ReferenceSha256Parser.fromHeaders(
                xChecksumSha256 = null,
                contentDigest = "sha-256=:!!!not-base64!!! :",
                digest = "sha-256=not base64",
            ),
        )
        assertNull(
            ReferenceSha256Parser.fromHeaders(
                xChecksumSha256 = null,
                contentDigest = null,
                digest = "sha-256=k7iFrfYGEF2HxHvURM+lgA==",
            ),
        )
    }

    @Test
    fun metadataRejectsNonNormalizedReferenceChecksums() {
        DownloadMetadata(url = "https://example.com/file.bin", referenceSha256 = EMPTY_SHA256_HEX)
        assertThrows(IllegalArgumentException::class.java) {
            DownloadMetadata(url = "https://example.com/file.bin", referenceSha256 = EMPTY_SHA256_HEX.uppercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DownloadMetadata(url = "https://example.com/file.bin", referenceSha256 = "abc")
        }
        return
    }

    companion object {
        const val EMPTY_SHA256_HEX = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val EMPTY_SHA256_BASE64 = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
        const val ZERO_SHA256_HEX = "0000000000000000000000000000000000000000000000000000000000000000"
        const val ZERO_SHA256_BASE64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
