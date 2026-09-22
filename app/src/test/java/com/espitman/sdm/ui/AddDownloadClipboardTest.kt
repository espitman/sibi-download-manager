package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AddDownloadClipboardTest {
    @Test
    fun explicitBrowserHandoffWinsOverClipboard() {
        assertEquals(
            "https://browser.example/file.zip",
            initialDownloadUrl("https://browser.example/file.zip", "https://clipboard.example/other.zip"),
        )
    }

    @Test
    fun validHttpClipboardLinkPrefillsNewDownload() {
        assertEquals(
            "http://example.com/file.zip",
            initialDownloadUrl("", "  http://example.com/file.zip  "),
        )
        assertEquals("", initialDownloadUrl("", "ordinary clipboard text"))
    }
}
