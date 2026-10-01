package com.espitman.sdm.domain

import org.junit.Assert.*
import org.junit.Test

class LinkArchiveTest {
    @Test fun preservesSignedUrlsRejectsInvalidLinesAndCountsDuplicates() {
        val signed="https://example.org/file?token=a%2Fb&expires=123"
        val p=LinkArchive.preview("\uFEFF$signed\r\n$signed\nhttp://example.org/new\nftp://example.org/x\nhttps://user:pass@example.org/x\n",setOf(signed))
        assertEquals(listOf("http://example.org/new"),p.urls);assertEquals(2,p.duplicates);assertEquals(2,p.invalid)
    }
    @Test fun exportContainsOnlyUrlsNoMetadataOrSecrets() {
        val d=Download(url="http://example.org/x",fileName="secret-name",destinationPath="/private/secret",createdAtEpochMillis=1)
        assertEquals("http://example.org/x\n",LinkArchive.export(listOf(d,d)))
        assertEquals("",LinkArchive.export(emptyList()))
    }
    @Test fun handles5000LinesAndRejectsLargerListsAndFiles() {
        assertEquals(5000,LinkArchive.preview((1..5000).joinToString("\n") {"https://example.org/$it"},emptySet()).urls.size)
        assertThrows(IllegalArgumentException::class.java) {LinkArchive.preview((1..5001).joinToString("\n") {"https://example.org/$it"},emptySet())}
        assertThrows(IllegalArgumentException::class.java) {LinkArchive.preview("x".repeat(LinkArchive.MAX_FILE_BYTES+1),emptySet())}
    }
}
