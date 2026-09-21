package com.espitman.sdm.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class DownloadFilenameResolverTest {

    @Test
    fun precedenceRfcContentDispositionFilenameStarUtf8OverFilename() {
        val header = "attachment; filename=\"fallback.txt\"; filename*=UTF-8''%E6%B5%8B%E8%AF%95%E6%96%87%E6%A1%A3.pdf"
        val resolved = DownloadFilenameResolver.resolveFilename(header, "https://example.com/ignored.zip")
        assertEquals("测试文档.pdf", resolved)
    }

    @Test
    fun precedenceRfcContentDispositionFilenameStarCaseInsensitive() {
        val header = "attachment; FILENAME*=utf-8''hello%20world.tar.gz"
        val resolved = DownloadFilenameResolver.resolveFilename(header, "https://example.com/ignored.zip")
        assertEquals("hello world.tar.gz", resolved)
    }

    @Test
    fun precedenceFilenameOverUrlWhenFilenameStarAbsent() {
        val header = "attachment; filename=\"archive-v1.0.tar.gz\""
        val resolved = DownloadFilenameResolver.resolveFilename(header, "https://example.com/downloads/different_name.bin")
        assertEquals("archive-v1.0.tar.gz", resolved)
    }

    @Test
    fun precedenceUrlPathWhenNoContentDisposition() {
        val resolved = DownloadFilenameResolver.resolveFilename(null, "https://example.com/files/setup_installer.apk")
        assertEquals("setup_installer.apk", resolved)
    }

    @Test
    fun precedenceUrlPathWithPercentEncoding() {
        val resolved = DownloadFilenameResolver.resolveFilename(null, "https://example.com/files/my%20presentation%20%28final%29.pdf")
        assertEquals("my presentation (final).pdf", resolved)
    }

    @Test
    fun safeFallbackWhenDispositionAndUrlLackUsableFilename() {
        val resolved1 = DownloadFilenameResolver.resolveFilename(null, "https://example.com/")
        assertEquals(DownloadFilenameResolver.DEFAULT_FALLBACK_FILENAME, resolved1)

        val resolved2 = DownloadFilenameResolver.resolveFilename("", "https://example.com/???")
        assertEquals(DownloadFilenameResolver.DEFAULT_FALLBACK_FILENAME, resolved2)
    }

    @Test
    fun customSafeFallback() {
        val resolved = DownloadFilenameResolver.resolveFilename(null, "https://example.com/", fallback = "custom_file.bin")
        assertEquals("custom_file.bin", resolved)
    }

    @Test
    fun urlQueryAndFragmentAreExcluded() {
        val resolved = DownloadFilenameResolver.resolveFilename(
            null,
            "https://cdn.example.com/releases/v2.1/application.zip?token=secret123&version=latest#section1"
        )
        assertEquals("application.zip", resolved)
    }

    @Test
    fun urlWithQueryAndNoPathSegmentUsesFallback() {
        val resolved = DownloadFilenameResolver.resolveFilename(
            null,
            "https://example.com/?download=12345"
        )
        assertEquals(DownloadFilenameResolver.DEFAULT_FALLBACK_FILENAME, resolved)
    }

    @Test
    fun bareHostNeverUsedAsFilename() {
        val resolvedBare = DownloadFilenameResolver.resolveFilename(null, "https://example.com")
        assertEquals(DownloadFilenameResolver.DEFAULT_FALLBACK_FILENAME, resolvedBare)

        val resolvedSlash = DownloadFilenameResolver.resolveFilename(null, "https://example.com/")
        assertEquals(DownloadFilenameResolver.DEFAULT_FALLBACK_FILENAME, resolvedSlash)

        val resolvedWithQuery = DownloadFilenameResolver.resolveFilename(null, "https://example.com?query=1")
        assertEquals(DownloadFilenameResolver.DEFAULT_FALLBACK_FILENAME, resolvedWithQuery)
    }

    @Test
    fun toleratesMalformedHeadersGracefully() {
        // Missing closing quote, junk tokens, broken parameter syntax
        val header1 = "attachment; filename=\"unclosed_quote.pdf; other=param"
        val res1 = DownloadFilenameResolver.resolveFilename(header1, "https://example.com/fallback.bin")
        // Should extract or fall back gracefully without throwing
        assertEquals("unclosed_quote.pdf; other=param", res1)

        val header2 = "attachment; filename*=invalid-encoding'lang'%ZZ%ZZ"
        val res2 = DownloadFilenameResolver.resolveFilename(header2, "https://example.com/url_file.mp4")
        assertEquals("url_file.mp4", res2)

        val header3 = ";;;;;;====;;;\"\"\""
        val res3 = DownloadFilenameResolver.resolveFilename(header3, "https://example.com/valid.dmg")
        assertEquals("valid.dmg", res3)
    }

    @Test
    fun rfc5987PlusSignRemainsPlusAndMalformedFallsBack() {
        // In RFC 5987 / RFC 6266, '+' is not a space
        val headerPlus = "attachment; filename*=UTF-8''file+name%2Bmore.txt"
        val resPlus = DownloadFilenameResolver.resolveFilename(headerPlus, "https://example.com/ignored.bin")
        assertEquals("file+name+more.txt", resPlus)

        // Malformed percent encoding in filename* falls back to normal filename
        val headerMalformed = "attachment; filename=\"fallback.txt\"; filename*=UTF-8''bad%2name%G1"
        val resMalformed = DownloadFilenameResolver.resolveFilename(headerMalformed, "https://example.com/ignored.bin")
        assertEquals("fallback.txt", resMalformed)

        // Unknown charset falls back to normal filename
        val headerUnknownCharset = "attachment; filename=\"normal.pdf\"; filename*=UNKNOWN-CHARSET-XYZ''hello.pdf"
        val resUnknown = DownloadFilenameResolver.resolveFilename(headerUnknownCharset, "https://example.com/ignored.bin")
        assertEquals("normal.pdf", resUnknown)
    }

    @Test
    fun pathTraversalPreventionStripsSlashesAndBasenameExtracted() {
        // Relative traversal
        val resolved1 = DownloadFilenameResolver.resolveFilename("attachment; filename=\"../../../../etc/passwd\"", null)
        assertEquals("passwd", resolved1)

        // Windows absolute / backslash path
        val resolved2 = DownloadFilenameResolver.resolveFilename("attachment; filename=\"C:\\Windows\\System32\\calc.exe\"", null)
        assertEquals("calc.exe", resolved2)

        // URL path traversal
        val resolved3 = DownloadFilenameResolver.resolveFilename(null, "https://example.com/path/to/../../../secret.key")
        assertEquals("secret.key", resolved3)
    }

    @Test
    fun sanitizesHostileCharactersAndControls() {
        // Hostile chars: : * ? " < > |
        val dirty = "my:bad*file?name\"with<illegal>chars|.mp4"
        val sanitized = DownloadFilenameResolver.sanitize(dirty)
        assertEquals("my_bad_file_name_with_illegal_chars_.mp4", sanitized)

        // Control characters (e.g. \u0000, \u0007, \u001F, \u007F)
        val withControls = "test\u0000file\u0007control\u001Fname\u007F.txt"
        val sanitizedControls = DownloadFilenameResolver.sanitize(withControls)
        assertEquals("test_file_control_name_.txt", sanitizedControls)
    }

    @Test
    fun sanitizesDotAndDotDot() {
        assertNull(DownloadFilenameResolver.sanitize("."))
        assertNull(DownloadFilenameResolver.sanitize(".."))
        assertNull(DownloadFilenameResolver.sanitize("..."))
        assertNull(DownloadFilenameResolver.sanitize("   ...   "))
        assertNull(DownloadFilenameResolver.sanitize("   "))

        val fallbackDot = DownloadFilenameResolver.resolveFilename("attachment; filename=\"..\"", "https://example.com/.")
        assertEquals(DownloadFilenameResolver.DEFAULT_FALLBACK_FILENAME, fallbackDot)
    }

    @Test
    fun stripsLeadingAndTrailingUnsafeDotsAndSpaces() {
        val input = "  ...important_report.pdf...   "
        val sanitized = DownloadFilenameResolver.sanitize(input)
        assertEquals("important_report.pdf", sanitized)
    }

    @Test
    fun reservedDeviceNamesArePrefixed() {
        val names = listOf(
            "CON", "con", "prn", "AUX", "NUL",
            "COM1", "com9", "LPT1", "lpt9",
            "con.txt", "NUL.tar.gz", "com3.json"
        )
        for (name in names) {
            val sanitized = DownloadFilenameResolver.sanitize(name)
            assertEquals("_$name", sanitized)
        }
    }

    @Test
    fun preservesUnicodeCharacters() {
        val unicodeName = "日本語のファイル名_🚀_العربية_русский.zip"
        val sanitized = DownloadFilenameResolver.sanitize(unicodeName)
        assertEquals(unicodeName, sanitized)
    }

    @Test
    fun capsLengthWhilePreservingExtension() {
        val longBase = "a".repeat(300)
        val filename = "$longBase.tar.gz"
        val sanitized = DownloadFilenameResolver.sanitize(filename)!!

        assertEquals(255, sanitized.toByteArray(StandardCharsets.UTF_8).size)
        // Standard extension is the last dot-segment .gz
        assertEquals(".gz", sanitized.substring(sanitized.lastIndexOf('.')))
        // Check base is truncated properly
        assertEquals("a".repeat(255 - ".gz".length) + ".gz", sanitized)
    }

    @Test
    fun capsLengthWithoutExtension() {
        val longName = "b".repeat(300)
        val sanitized = DownloadFilenameResolver.sanitize(longName)!!

        assertEquals(255, sanitized.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals("b".repeat(255), sanitized)
    }

    @Test
    fun capsMultibyteCharactersWithoutSplittingCodePoints() {
        // Multi-byte Unicode: '🔥' is 4 bytes in UTF-8 (2 chars as surrogate pair)
        // '日' is 3 bytes in UTF-8
        val baseEmoji = "🔥".repeat(70) // 70 * 4 = 280 bytes
        val filename = "$baseEmoji.txt" // ext is 4 bytes
        val sanitized = DownloadFilenameResolver.sanitize(filename)!!

        val bytes = sanitized.toByteArray(StandardCharsets.UTF_8)
        assertTrue(bytes.size <= 255)
        assertTrue(sanitized.endsWith(".txt"))
        // Max emoji allowed: (255 - 4) / 4 = 251 / 4 = 62 emojis (62 * 4 + 4 = 252 bytes)
        assertEquals("🔥".repeat(62) + ".txt", sanitized)
        assertEquals(252, sanitized.toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun collisionResolverReturnsOriginalIfNotExisting() {
        val resolved = DownloadFilenameResolver.resolveCollision("report.pdf") { false }
        assertEquals("report.pdf", resolved)
    }

    @Test
    fun collisionResolverProducesNumberedSuffixWithoutOverwriting() {
        val existing = setOf("report.pdf", "report (1).pdf", "report (2).pdf")
        val resolved = DownloadFilenameResolver.resolveCollision("report.pdf") { candidate ->
            candidate in existing
        }
        assertEquals("report (3).pdf", resolved)
    }

    @Test
    fun collisionResolverHandlesNoExtension() {
        val existing = setOf("README", "README (1)")
        val resolved = DownloadFilenameResolver.resolveCollision("README") { candidate ->
            candidate in existing
        }
        assertEquals("README (2)", resolved)
    }

    @Test
    fun collisionResolverTruncatesBaseToRespectMaxFilenameLength() {
        val baseName = "a".repeat(251) + ".txt" // 251 + 4 = 255 chars/bytes
        val existing = setOf(baseName)
        val resolved = DownloadFilenameResolver.resolveCollision(baseName) { candidate ->
            candidate in existing
        }
        // Suffix " (1)" is 4 chars + ".txt" (4 chars) = 8 chars. Base truncated to 255 - 8 = 247 chars.
        assertEquals(255, resolved.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals("a".repeat(247) + " (1).txt", resolved)
    }

    @Test
    fun reservationCallbackEnforcesAtomicSemantics() {
        val createdFiles = mutableSetOf<String>()
        val attempts = mutableListOf<String>()

        fun tryCreateNewFile(candidate: String): Boolean {
            attempts.add(candidate)
            if (candidate in createdFiles) {
                return false // FileAlreadyExistsException
            }
            createdFiles.add(candidate)
            return true
        }

        createdFiles.add("photo.jpg")
        createdFiles.add("photo (1).jpg")

        val reserved = DownloadFilenameResolver.resolveReservation("photo.jpg") { candidate ->
            tryCreateNewFile(candidate)
        }

        assertEquals("photo (2).jpg", reserved)
        assertTrue(createdFiles.contains("photo (2).jpg"))
        assertEquals(listOf("photo.jpg", "photo (1).jpg", "photo (2).jpg"), attempts)
    }

    @Test
    fun metadataSuggestedFilenameIntegration() {
        val metadata = DownloadMetadata(
            url = "https://example.com/path/doc.docx",
            contentDisposition = "attachment; filename=\"custom_name.docx\""
        )
        assertEquals("custom_name.docx", metadata.suggestedFilename)

        val metadataFromUrl = DownloadMetadata(
            url = "https://example.com/files/download.zip"
        )
        assertEquals("download.zip", metadataFromUrl.suggestedFilename)
    }
}
